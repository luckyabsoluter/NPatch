package top.nkbe.npatch.patch;

import com.google.gson.Gson;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import pxb.android.axml.AxmlReader;
import pxb.android.axml.AxmlVisitor;
import pxb.android.axml.AxmlWriter;
import pxb.android.axml.NodeVisitor;
import top.nkbe.npatch.share.PatchConfig;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;
import static top.nkbe.npatch.share.Constants.CONFIG_ASSET_PATH;

public class PermissionIsolationTest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String SOURCE = "example.permissionprobe";
    private static final String CLONE = "example.permissionprobe.clone";
    private static final String OWNED = "example.vendor.permission.PRIVATE";
    private static final String EXTERNAL = SOURCE + ".permission.EXTERNAL";
    private static final String GROUP = "example.vendor.permission.GROUP";
    private static final String RECEIVER = SOURCE + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION";
    
    @Rule public TemporaryFolder directory = new TemporaryFolder();
    
    @Test public void rewritesOwnedReferencesAcrossBaseAndSplitWithoutChangingProtectionOrExternalPermissions() throws Exception {
        byte[] base = manifest(SOURCE, false, OWNED, true, "permission");
        byte[] split = manifest(SOURCE, true, OWNED, false, "permission");
        Path state = directory.newFolder().toPath();
        PermissionIsolation isolation = PermissionIsolation.prepare(
                List.of(apk(split, null), apk(base, null)), CLONE, state, null);
        String isolated = isolation.mapping.permissions.get(OWNED);
        assertNotEquals(OWNED, isolated);
        assertFalse(isolation.mapping.permissions.containsKey(EXTERNAL));
        assertEquals(isolation.mapping.permissions.get(RECEIVER), isolation.mapping.receiverPermission);
        
        var baseAttrs = attributes(isolation.rewrite(base, false));
        assertTrue(baseAttrs.containsValue(isolated));
        assertEquals(isolated, baseAttrs.get("manifest/permission[1]/name"));
        assertEquals(2, baseAttrs.get("manifest/permission[1]/protectionLevel"));
        assertEquals(isolation.mapping.groups.get(GROUP), baseAttrs.get("manifest/permission[1]/permissionGroup"));
        assertEquals(isolation.mapping.groups.get(GROUP), baseAttrs.get("manifest/permission-group[1]/name"));
        assertEquals(isolated, baseAttrs.get("manifest/uses-permission[1]/name"));
        assertEquals(EXTERNAL, baseAttrs.get("manifest/uses-permission[2]/name"));
        assertEquals(isolated, baseAttrs.get("manifest/uses-permission-sdk-23[1]/name"));
        
        var splitAttrs = attributes(isolation.rewrite(split, false));
        String provider = "manifest/application[1]/provider[1]";
        assertEquals(isolated, splitAttrs.get("manifest/application[1]/receiver[1]/permission"));
        assertEquals(isolated, splitAttrs.get(provider + "/readPermission"));
        assertEquals(EXTERNAL, splitAttrs.get(provider + "/writePermission"));
        assertEquals(isolated, splitAttrs.get(provider + "/path-permission[1]/permission"));
    }
    
    @Test public void keepsTheInstanceOnUpdateAndRestoresItFromPreviousApk() throws Exception {
        File base = apk(manifest(SOURCE, false, OWNED, true, "permission"), null);
        Path state = directory.newFolder().toPath();
        PermissionIsolation first = PermissionIsolation.prepare(List.of(base), CLONE, state, null);
        PermissionIsolation again = PermissionIsolation.prepare(List.of(base), CLONE, state, null);
        PermissionIsolation secondClone = PermissionIsolation.prepare(List.of(base), CLONE + "2", state, null);
        assertEquals(first.mapping.permissions, again.mapping.permissions);
        assertNotEquals(first.mapping.permissions, secondClone.mapping.permissions);
        
        PatchConfig config = new PatchConfig(false, false, false, 0, 0, null, null, false, false, CLONE, false, false);
        config.permissionMapping = first.mapping;
        File previous = apk(manifest(CLONE, false, first.mapping.permissions.get(OWNED), true, "permission"), config);
        byte[] previousSplit = manifest(CLONE, true, first.mapping.permissions.get(OWNED), false, "permission");
        PermissionIsolation restored = PermissionIsolation.prepare(
                List.of(base, apk(previousSplit, null)), CLONE, directory.newFolder().toPath(), previous);
        assertEquals(first.mapping.permissions, restored.mapping.permissions);
        assertEquals(first.mapping.permissions.get(OWNED), attributes(restored.rewrite(previousSplit, true))
                .get("manifest/application[1]/receiver[1]/permission"));
    }
    
    @Test public void reportsAmbiguousOwnershipAndDoesNotCreateStateForUnchangedPackage() throws Exception {
        Path state = directory.newFolder().toPath().resolve("state");
        File tree = apk(manifest(SOURCE, false, OWNED, true, "permission-tree"), null);
        assertNull(PermissionIsolation.prepare(List.of(tree), SOURCE, state, null));
        IOException treeError = assertThrows(IOException.class,
                () -> PermissionIsolation.prepare(List.of(tree), CLONE, state, null));
        assertTrue(treeError.getMessage().contains("permission-tree"));
        File base = apk(manifest(SOURCE, false, OWNED, false, "permission"), null);
        File split = apk(manifest(SOURCE, true, OWNED, true, "permission"), null);
        IOException splitError = assertThrows(IOException.class,
                () -> PermissionIsolation.prepare(List.of(base, split), CLONE, state, null));
        assertTrue(splitError.getMessage().contains("only in a split APK"));
        assertFalse(Files.exists(state));
    }
    
    private File apk(byte[] manifest, PatchConfig config) throws Exception {
        File apk = directory.newFile();
        try (var output = new ZipOutputStream(Files.newOutputStream(apk.toPath()))) {
            output.putNextEntry(new ZipEntry("AndroidManifest.xml"));
            output.write(manifest);
            output.closeEntry();
            if (config != null) {
                output.putNextEntry(new ZipEntry(CONFIG_ASSET_PATH));
                output.write(new Gson().toJson(config).getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return apk;
    }
    
    private static byte[] manifest(String packageName, boolean split, String permission, boolean own, String declaration) throws Exception {
        AxmlWriter writer = new AxmlWriter();
        writer.ns("android", ANDROID, 1);
        NodeVisitor root = writer.child(null, "manifest");
        root.attr(null, "package", -1, NodeVisitor.TYPE_STRING, packageName);
        if (split) root.attr(null, "split", -1, NodeVisitor.TYPE_STRING, "feature");
        if (own) {
            NodeVisitor group = root.child(null, "permission-group");
            attr(group, "name", 0x01010003, GROUP);
            group.end();
            NodeVisitor local = root.child(null, declaration);
            attr(local, "name", 0x01010003, permission);
            attr(local, "permissionGroup", 0x0101000a, GROUP);
            local.attr(ANDROID, "protectionLevel", 0x01010009, NodeVisitor.TYPE_FIRST_INT, 2);
            local.end();
            named(root, "permission", RECEIVER);
        }
        named(root, "uses-permission", permission);
        named(root, "uses-permission", EXTERNAL);
        named(root, "uses-permission-sdk-23", permission);
        NodeVisitor app = root.child(null, "application");
        NodeVisitor receiver = app.child(null, "receiver");
        attr(receiver, "permission", 0x01010006, permission);
        receiver.end();
        NodeVisitor provider = app.child(null, "provider");
        attr(provider, "readPermission", 0x01010007, permission);
        attr(provider, "writePermission", 0x01010008, EXTERNAL);
        NodeVisitor path = provider.child(null, "path-permission");
        attr(path, "permission", 0x01010006, permission);
        path.end();
        provider.end();
        app.end();
        root.end();
        writer.end();
        return writer.toByteArray();
    }
    
    private static void named(NodeVisitor parent, String tag, String name) {
        NodeVisitor node = parent.child(null, tag);
        attr(node, "name", 0x01010003, name);
        node.end();
    }
    
    private static void attr(NodeVisitor node, String name, int id, String value) {
        node.attr(ANDROID, name, id, NodeVisitor.TYPE_STRING, value);
    }
    
    private static Map<String, Object> attributes(byte[] xml) throws IOException {
        Map<String, Object> attributes = new LinkedHashMap<>();
        new AxmlReader(xml).accept(new AxmlVisitor() {
            @Override public NodeVisitor child(String ns, String name) {
                return attributeReader(name, attributes);
            }
        });
        return attributes;
    }
    
    private static NodeVisitor attributeReader(String path, Map<String, Object> attributes) {
        return new NodeVisitor() {
            private final Map<String, Integer> counts = new LinkedHashMap<>();
            
            @Override public NodeVisitor child(String ns, String name) {
                int index = counts.merge(name, 1, Integer::sum);
                return attributeReader(path + "/" + name + "[" + index + "]", attributes);
            }
            
            @Override public void attr(String ns, String name, int id, int type, Object value) {
                attributes.put(path + "/" + name, value);
            }
        };
    }
}
