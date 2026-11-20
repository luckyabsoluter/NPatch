package top.nkbe.npatch.share;

import java.util.Map;

public final class PermissionMapping {
    public final String sourcePackage;
    public final String instanceId;
    public final Map<String, String> permissions;
    public final Map<String, String> groups;
    public final String receiverPermission;
    
    public PermissionMapping(String sourcePackage, String instanceId,
                             Map<String, String> permissions, Map<String, String> groups,
                             String receiverPermission) {
        this.sourcePackage = sourcePackage;
        this.instanceId = instanceId;
        this.permissions = permissions;
        this.groups = groups;
        this.receiverPermission = receiverPermission;
    }
}
