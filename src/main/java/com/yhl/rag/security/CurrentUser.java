package com.yhl.rag.security;

import java.util.Set;

public class CurrentUser {

    private String tenantId;

    private String userId;

    private String department;

    private Set<String> departmentIds = Set.of();

    private Set<String> roleIds = Set.of();

    private int permissionLevel;

    public CurrentUser() {
    }

    public CurrentUser(String userId, String department, int permissionLevel) {
        this("tenant-default", userId, department, Set.of(department), Set.of("customer"), permissionLevel);
    }

    public CurrentUser(
            String tenantId,
            String userId,
            String department,
            Set<String> departmentIds,
            Set<String> roleIds,
            int permissionLevel
    ) {
        this.tenantId = tenantId;
        this.userId = userId;
        this.department = department;
        this.departmentIds = departmentIds == null ? Set.of() : Set.copyOf(departmentIds);
        this.roleIds = roleIds == null ? Set.of() : Set.copyOf(roleIds);
        this.permissionLevel = permissionLevel;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public Set<String> getDepartmentIds() {
        return departmentIds;
    }

    public void setDepartmentIds(Set<String> departmentIds) {
        this.departmentIds = departmentIds == null ? Set.of() : Set.copyOf(departmentIds);
    }

    public Set<String> getRoleIds() {
        return roleIds;
    }

    public void setRoleIds(Set<String> roleIds) {
        this.roleIds = roleIds == null ? Set.of() : Set.copyOf(roleIds);
    }

    public int getPermissionLevel() {
        return permissionLevel;
    }

    public void setPermissionLevel(int permissionLevel) {
        this.permissionLevel = permissionLevel;
    }
}
