package example.permissionprobe;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

public final class PermissionProbeActivity extends Activity {
    private static final String PRIVATE_PERMISSION = "example.vendor.permission.PRIVATE";
    private static final String RECEIVER_SUFFIX = ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION";
    private static final String ACTION = "example.permissionprobe.PROBE";
    private BroadcastReceiver receiver;
    
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView status = new TextView(this);
        setContentView(status);
        String receiverPermission = getPackageName() + RECEIVER_SUFFIX;
        boolean staticNameGranted = checkSelfPermission(PRIVATE_PERMISSION) == PackageManager.PERMISSION_GRANTED;
        boolean dynamicNameGranted = checkSelfPermission(receiverPermission) == PackageManager.PERMISSION_GRANTED;
        boolean unknownNameDenied = checkSelfPermission("example.permissionprobe.UNDEFINED") == PackageManager.PERMISSION_DENIED;
        boolean externalNameGranted = checkSelfPermission("android.permission.INTERNET") == PackageManager.PERMISSION_GRANTED;
        String checks = getPackageName() + "\nLiteral permission granted = " + staticNameGranted
                + "\nGenerated permission granted = " + dynamicNameGranted
                + "\nUnknown permission denied = " + unknownNameDenied
                + "\nExternal permission granted = " + externalNameGranted;
        status.setText(checks + "\nWaiting for protected broadcast");
        BroadcastReceiver probe = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                status.setText(checks + "\nProtected broadcast received = true");
                Log.i("NPatchPermissionProbe", checks.replace('\n', ' ') + " Protected broadcast received = true");
            }
        };
        IntentFilter filter = new IntentFilter(ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(probe, filter, receiverPermission, null, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(probe, filter, receiverPermission, null);
        }
        receiver = probe;
        sendBroadcast(new Intent(ACTION).setPackage(getPackageName()));
    }
    
    @Override protected void onDestroy() {
        if (receiver != null) unregisterReceiver(receiver);
        super.onDestroy();
    }
}
