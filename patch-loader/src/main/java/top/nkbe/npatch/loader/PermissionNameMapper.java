package top.nkbe.npatch.loader;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import top.nkbe.npatch.share.PermissionMapping;

final class PermissionNameMapper {
    private static final String TAG = "NPatch-PermissionMapping";
    private static final String RECEIVER_PERMISSION = ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION";
    private static final String RESULT_NAMES = "android.content.pm.extra.REQUEST_PERMISSIONS_NAMES";
    
    static void install(Context context, PermissionMapping mapping) {
        if (mapping == null || mapping.permissions == null) return;
        String host = context.getPackageName();
        Class<?> pmClass = context.getPackageManager().getClass();
        if (mapping.groups != null && !mapping.groups.isEmpty()) {
            hookArgument(pmClass, "getPermissionGroupInfo", 0, String.class, call -> true,
                    name -> mapping.groups.getOrDefault(name, name));
        }
        if (mapping.permissions.isEmpty()) return;
        
        UnaryOperator<String> permission = name -> mapping.permissions.getOrDefault(name, name);
        String hostReceiver = host + RECEIVER_PERMISSION;
        UnaryOperator<String> receiver = name -> hostReceiver.equals(name) && mapping.receiverPermission != null
                ? mapping.permissions.getOrDefault(name, mapping.receiverPermission) : permission.apply(name);
        Predicate<XC_MethodHook.MethodHookParam> hostContext = call -> call.thisObject instanceof Context caller
                && host.equals(caller.getPackageName());
        
        Class<?> contextClass = context.getClass();
        for (String method : new String[]{"checkPermission", "checkSelfPermission", "checkCallingPermission",
                "checkCallingOrSelfPermission", "enforcePermission", "enforceCallingPermission",
                "enforceCallingOrSelfPermission"}) {
            hookArgument(contextClass, method, 0, String.class, hostContext, receiver);
        }
        hookArgument(contextClass, "registerReceiver", 2, String.class, hostContext, receiver);
        hookArgument(contextClass, "registerReceiverAsUser", 3, String.class, hostContext, receiver);
        for (String method : new String[]{"sendBroadcast", "sendOrderedBroadcast"}) {
            hookArgument(contextClass, method, 1, String.class, hostContext, permission);
        }
        for (String method : new String[]{"sendBroadcastAsUser", "sendOrderedBroadcastAsUser"}) {
            hookArgument(contextClass, method, 2, String.class, hostContext, permission);
        }
        
        hookArgument(pmClass, "checkPermission", 0, String.class,
                call -> call.args.length > 1 && host.equals(call.args[1]), receiver);
        hookArgument(pmClass, "getPermissionInfo", 0, String.class, call -> true, permission);
        hookArgument(Activity.class, "shouldShowRequestPermissionRationale", 0, String.class, hostContext, permission);
        hookArgument(PackageManager.class, "buildRequestPermissionsIntent", 0, String[].class, call -> true,
                names -> mapNames(names, permission));
        
        Map<String, String> originalNames = new HashMap<>();
        mapping.permissions.forEach((original, isolated) -> originalNames.put(isolated, original));
        for (String method : new String[]{"dispatchRequestPermissionsResult", "dispatchRequestPermissionsResultToFragment"}) {
            hookArgument(Activity.class, method, 1, Intent.class, hostContext, intent -> {
                String[] names = intent.getStringArrayExtra(RESULT_NAMES);
                String[] originals = mapNames(names, name -> originalNames.getOrDefault(name, name));
                return originals == names ? intent : new Intent(intent).putExtra(RESULT_NAMES, originals);
            });
        }
    }
    
    static String[] mapNames(String[] names, UnaryOperator<String> mapper) {
        if (names == null) return null;
        String[] result = names;
        for (int i = 0; i < names.length; i++) {
            if (names[i] == null) continue;
            String mapped = mapper.apply(names[i]);
            if (!Objects.equals(mapped, names[i])) {
                if (result == names) result = names.clone();
                result[i] = mapped;
            }
        }
        return result;
    }
    
    private static <T> void hookArgument(Class<?> owner, String name, int index, Class<T> type,
                                          Predicate<XC_MethodHook.MethodHookParam> scope, UnaryOperator<T> mapper) {
        boolean hooked = false;
        try {
            for (Method method : owner.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (!name.equals(method.getName()) || parameters.length <= index || parameters[index] != type) continue;
                try {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam call) {
                            if (call.args[index] != null && scope.test(call)) {
                                call.args[index] = mapper.apply(type.cast(call.args[index]));
                            }
                        }
                    });
                    hooked = true;
                } catch (RuntimeException | LinkageError error) {
                    Log.w(TAG, "Could not install permission mapping for " + name, error);
                }
            }
        } catch (RuntimeException | LinkageError error) {
            Log.w(TAG, "Could not inspect permission API " + name, error);
        }
        if (!hooked) Log.d(TAG, "Permission API unavailable: " + name);
    }
}
