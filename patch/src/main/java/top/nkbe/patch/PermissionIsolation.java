package top.nkbe.npatch.patch;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import org.apache.commons.io.IOUtils;
import pxb.android.axml.AxmlReader;
import pxb.android.axml.AxmlVisitor;
import pxb.android.axml.AxmlWriter;
import pxb.android.axml.NodeVisitor;
import top.nkbe.npatch.share.PatchConfig;
import top.nkbe.npatch.share.PermissionMapping;

import java.io.File;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

import static top.nkbe.npatch.share.Constants.CONFIG_ASSET_PATH;
import top.nkbe.npatch.patch.util.ManifestParser;

final class PermissionIsolation {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final Set<String> REQUESTS = new HashSet<>(Arrays.asList(
            "uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m"));
    private static final Set<String> COMPONENTS = new HashSet<>(Arrays.asList(
            "activity", "activity-alias", "service", "receiver", "provider"));
    
    private enum Role { PERMISSION, GROUP, REQUEST, GUARD, GROUP_REFERENCE, TREE }
    
    private interface AttributeVisitor {
        Object visit(Role role, Object value);
    }
    
    private record Manifest(String packageName, boolean split, byte[] xml) {}
    
    final PermissionMapping mapping;
    private final Map<String, String> previousPermissions;
    private final Map<String, String> previousGroups;
    
    private PermissionIsolation(PermissionMapping mapping, PermissionMapping previous) {
        this.mapping = mapping;
        previousPermissions = reverse(previous == null ? null : previous.permissions);
        previousGroups = reverse(previous == null ? null : previous.groups);
    }
    
    static PermissionIsolation prepare(List<File> files, String requestedPackage, Path stateDirectory,
                                       File previousApk) throws IOException {
        PatchConfig previous = previousApk == null ? null : readPreviousConfig(previousApk);
        PermissionMapping previousMapping = previous == null ? null : previous.permissionMapping;
        var manifests = new ArrayList<Manifest>();
        for (File file : files) {
            try (ZipFile zip = new ZipFile(file)) {
                var entry = zip.getEntry("AndroidManifest.xml");
                if (entry == null) throw new IOException("APK has no AndroidManifest.xml");
                try (var input = zip.getInputStream(entry)) {
                    byte[] xml = IOUtils.toByteArray(input);
                    var identity = ManifestParser.parseManifestFile(new ByteArrayInputStream(xml));
                    if (identity == null || identity.packageName == null) {
                        throw new IOException("Unable to read APK manifest package");
                    }
                    if (zip.getEntry(CONFIG_ASSET_PATH) != null) {
                        throw new IOException("Provide the original base APK and pass the previous patched base with --previous-apk");
                    }
                    manifests.add(new Manifest(identity.packageName,
                            identity.splitName != null && !identity.splitName.isEmpty(), xml));
                }
            }
        }
        var bases = manifests.stream().filter(manifest -> !manifest.split()).collect(Collectors.toList());
        if (bases.size() != 1) throw new IOException("Permission isolation requires exactly one base APK");
        String source = bases.get(0).packageName();
        String target = requestedPackage == null || requestedPackage.isEmpty() ? source : requestedPackage;
        if (target.equals(source)) {
            if (previousMapping != null) throw new IOException("An isolated update must retain its patched package name");
            return null;
        }
        if (previousMapping != null && (!source.equals(previousMapping.sourcePackage) || !target.equals(previous.newPackage))) {
            throw new IOException("Previous permission mapping belongs to a different source or patched package");
        }
        if (previousMapping != null && (previousMapping.instanceId == null || previousMapping.permissions == null
                || previousMapping.groups == null || !previousMapping.permissions.equals(namesFor(
                        previousMapping.permissions.keySet(), previousMapping.instanceId, "p"))
                || !previousMapping.groups.equals(namesFor(previousMapping.groups.keySet(), previousMapping.instanceId, "g")))) {
            throw new IOException("Previous APK contains an invalid permission mapping");
        }
        
        Map<String, String> oldPermissions = reverse(previousMapping == null ? null : previousMapping.permissions);
        Map<String, String> oldGroups = reverse(previousMapping == null ? null : previousMapping.groups);
        Set<String> permissions = new LinkedHashSet<>();
        Set<String> groups = new LinkedHashSet<>();
        Set<String> splitPermissions = new LinkedHashSet<>();
        Set<String> splitGroups = new LinkedHashSet<>();
        Set<String> externalCandidates = new LinkedHashSet<>();
        Set<String> externalGroupCandidates = new LinkedHashSet<>();
        Set<String> previousPermissionReferences = new LinkedHashSet<>();
        Set<String> previousGroupReferences = new LinkedHashSet<>();
        try {
            for (Manifest manifest : manifests) {
                if (!source.equals(manifest.packageName()) && !(manifest.split() && previous != null
                        && target.equals(manifest.packageName()) && target.equals(previous.newPackage))) {
                    throw new IOException("Permission isolation requires APKs from the same package");
                }
                boolean previouslyPatched = !source.equals(manifest.packageName());
                visit(manifest.xml(), null, (role, value) -> {
                    if (!(value instanceof String name) || name.trim().isEmpty()) {
                        if (role == Role.GUARD && "".equals(value)) return value;
                        throw new IllegalArgumentException("Cannot resolve a non-literal manifest permission attribute");
                    }
                    switch (role) {
                        case PERMISSION -> (manifest.split() ? splitPermissions : permissions)
                                .add(original(name, oldPermissions, previouslyPatched));
                        case GROUP -> (manifest.split() ? splitGroups : groups)
                                .add(original(name, oldGroups, previouslyPatched));
                        case TREE -> throw new IllegalArgumentException("Permission isolation does not support permission-tree or dynamically defined permissions");
                        case REQUEST, GUARD -> {
                            externalCandidates.add(original(name, oldPermissions, previouslyPatched));
                            if (previouslyPatched && oldPermissions.containsKey(name)) {
                                previousPermissionReferences.add(oldPermissions.get(name));
                            }
                        }
                        case GROUP_REFERENCE -> {
                            externalGroupCandidates.add(original(name, oldGroups, previouslyPatched));
                            if (previouslyPatched && oldGroups.containsKey(name)) previousGroupReferences.add(oldGroups.get(name));
                        }
                    }
                    return value;
                });
            }
        } catch (IllegalArgumentException error) {
            throw new IOException(error.getMessage(), error);
        }
        if (!permissions.containsAll(splitPermissions) || !groups.containsAll(splitGroups)) {
            throw new IOException("Cannot establish ownership of a permission or group declared only in a split APK");
        }
        if (!permissions.containsAll(previousPermissionReferences) || !groups.containsAll(previousGroupReferences)) {
            throw new IOException("A reused split refers to a removed permission or group. Supply the matching original split APKs.");
        }
        if (permissions.isEmpty() && groups.isEmpty() && previousMapping == null) return null;
        String id = instanceId(stateDirectory, source, target, previousMapping);
        Map<String, String> isolatedPermissions = namesFor(permissions, id, "p");
        Map<String, String> isolatedGroups = namesFor(groups, id, "g");
        externalCandidates.removeAll(permissions);
        externalGroupCandidates.removeAll(groups);
        if (isolatedPermissions.values().stream().anyMatch(name -> permissions.contains(name) || externalCandidates.contains(name))
                || isolatedGroups.values().stream().anyMatch(name -> groups.contains(name) || externalGroupCandidates.contains(name))) {
            throw new IOException("An isolated permission or group name conflicts with a source manifest name");
        }
        String receiverAlias = target + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION";
        String receiverPermission = isolatedPermissions.get(source + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION");
        if (externalCandidates.contains(receiverAlias)) receiverPermission = null;
        return new PermissionIsolation(new PermissionMapping(source, id, isolatedPermissions, isolatedGroups,
                receiverPermission), previousMapping);
    }
    
    byte[] rewrite(byte[] xml, boolean previouslyPatched) throws IOException {
        AxmlWriter writer = new AxmlWriter();
        visit(xml, writer, (role, value) -> {
            if (!(value instanceof String name)) return value;
            return switch (role) {
                case PERMISSION, REQUEST, GUARD -> mapped(name, mapping.permissions, previousPermissions,
                        previouslyPatched, role == Role.PERMISSION);
                case GROUP, GROUP_REFERENCE -> mapped(name, mapping.groups, previousGroups,
                        previouslyPatched, role == Role.GROUP);
                default -> value;
            };
        });
        return writer.toByteArray();
    }
    
    private static String mapped(String name, Map<String, String> names, Map<String, String> oldNames,
                                 boolean previouslyPatched, boolean declaration) {
        String original = original(name, oldNames, previouslyPatched);
        if (declaration && !names.containsKey(original)) {
            throw new IllegalArgumentException("Manifest permission declarations changed after analysis");
        }
        return names.getOrDefault(original, original);
    }
    
    private static String original(String name, Map<String, String> oldNames, boolean previouslyPatched) {
        return previouslyPatched ? oldNames.getOrDefault(name, name) : name;
    }
    
    private static Map<String, String> reverse(Map<String, String> names) {
        Map<String, String> result = new LinkedHashMap<>();
        if (names != null) names.forEach((original, isolated) -> result.put(isolated, original));
        return result;
    }
    
    private static PatchConfig readPreviousConfig(File apk) throws IOException {
        try (ZipFile zip = new ZipFile(apk)) {
            var entry = zip.getEntry(CONFIG_ASSET_PATH);
            if (entry == null) throw new IOException("Previous APK has no NPatch configuration");
            try (var reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                PatchConfig config = new Gson().fromJson(reader, PatchConfig.class);
                if (config == null) throw new IOException("Previous APK has an empty NPatch configuration");
                return config;
            } catch (JsonParseException error) {
                throw new IOException("Previous APK has an invalid NPatch configuration", error);
            }
        }
    }
    
    private static Map<String, String> namesFor(Set<String> names, String instanceId, String kind) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String name : names) {
            result.put(name, "npatch." + kind + identifier(instanceId + '\n' + name));
        }
        return result;
    }
    
    private static String identifier(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            ByteBuffer bytes = ByteBuffer.wrap(digest);
            return new UUID(bytes.getLong(), bytes.getLong()).toString().replace("-", "");
        } catch (NoSuchAlgorithmException error) {
            throw new AssertionError(error);
        }
    }
    
    private static synchronized String instanceId(Path directory, String source, String target,
                                                  PermissionMapping previous) throws IOException {
        Files.createDirectories(directory);
        String key = identifier(source + '\n' + target);
        Path state = directory.resolve(key + ".id");
        try (var channel = FileChannel.open(directory.resolve(key + ".lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE); var lock = channel.lock()) {
            String remembered = Files.exists(state) ? new String(Files.readAllBytes(state), StandardCharsets.UTF_8) : null;
            String priorId = previous == null ? null : previous.instanceId;
            if (previous != null && priorId == null) throw new IOException("Previous APK has no permission instance identifier");
            if (remembered != null && priorId != null && !remembered.equals(priorId)) {
                throw new IOException("Stored permission instance differs from the previous patched APK");
            }
            String id = remembered != null ? remembered : priorId != null ? priorId : UUID.randomUUID().toString();
            try {
                if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
            } catch (IllegalArgumentException error) {
                throw new IOException("Invalid stored permission instance identifier", error);
            }
            if (remembered == null) {
                Path pending = Files.createTempFile(directory, key, ".pending");
                try {
                    Files.write(pending, id.getBytes(StandardCharsets.UTF_8));
                    Files.move(pending, state, StandardCopyOption.ATOMIC_MOVE);
                } finally {
                    Files.deleteIfExists(pending);
                }
            }
            return id;
        }
    }
    
    private static void visit(byte[] xml, AxmlWriter writer, AttributeVisitor visitor) throws IOException {
        new AxmlReader(xml).accept(new AxmlVisitor(writer) {
            @Override public NodeVisitor child(String namespace, String name) {
                return new PermissionNode(super.child(namespace, name), "", namespace == null ? name : "", visitor);
            }
        });
    }
    
    private static final class PermissionNode extends NodeVisitor {
        private final String parent;
        private final String element;
        private final AttributeVisitor visitor;
        
        PermissionNode(NodeVisitor writer, String parent, String element, AttributeVisitor visitor) {
            super(writer);
            this.parent = parent;
            this.element = element;
            this.visitor = visitor;
        }
        
        @Override public NodeVisitor child(String namespace, String name) {
            return new PermissionNode(super.child(namespace, name), element, namespace == null ? name : "", visitor);
        }
        
        @Override public void attr(String ns, String name, int resourceId, int type, Object value) {
            Role role = ANDROID.equals(ns) ? role(name) : null;
            super.attr(ns, name, resourceId, type, role == null ? value : visitor.visit(role, value));
        }
        
        private Role role(String attribute) {
            if ("manifest".equals(parent)) {
                if ("permission".equals(element) && "permissionGroup".equals(attribute)) return Role.GROUP_REFERENCE;
                if ("name".equals(attribute)) {
                    if ("permission".equals(element)) return Role.PERMISSION;
                    if ("permission-group".equals(element)) return Role.GROUP;
                    if ("permission-tree".equals(element)) return Role.TREE;
                    if (REQUESTS.contains(element)) return Role.REQUEST;
                }
            }
            boolean provider = "provider".equals(element) && "application".equals(parent);
            boolean path = "path-permission".equals(element) && "provider".equals(parent);
            if (provider || path) {
                if ("readPermission".equals(attribute) || "writePermission".equals(attribute)) return Role.GUARD;
            }
            if ("permission".equals(attribute) && (path
                    || ("manifest".equals(parent) && "application".equals(element))
                    || ("application".equals(parent) && COMPONENTS.contains(element)))) return Role.GUARD;
            return null;
        }
    }
}
