package com.yhl.rag.tool;

import java.util.Set;

import com.yhl.rag.security.CurrentUser;

public class ToolExecutionContext {

    private String requestId;

    private String tenantId = "tenant-default";

    private String userId;

    private String department;

    private Set<String> departmentIds = Set.of();

    private int permissionLevel;

    private Set<String> roles = Set.of();

    private Set<String> permissions = Set.of();

    private boolean confirmedHighRiskExecution;

    public ToolExecutionContext() {
    }

    public ToolExecutionContext(String requestId, String userId, String department, int permissionLevel) {
        this(requestId, userId, department, permissionLevel, Set.of("customer"), Set.of("order:query", "order:cancel", "knowledge:search"));
    }

    public ToolExecutionContext(
            String requestId,
            String userId,
            String department,
            int permissionLevel,
            Set<String> roles,
            Set<String> permissions
    ) {
        this.requestId = requestId;
        this.userId = userId;
        this.department = department;
        this.departmentIds = department == null ? Set.of() : Set.of(department);
        this.permissionLevel = permissionLevel;
        this.roles = roles == null ? Set.of() : Set.copyOf(roles);
        this.permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    public static ToolExecutionContext from(String requestId, CurrentUser currentUser) {
        return new ToolExecutionContext(
                requestId,
                currentUser.getTenantId(),
                currentUser.getUserId(),
                currentUser.getDepartment(),
                currentUser.getDepartmentIds(),
                currentUser.getPermissionLevel(),
                currentUser.getRoleIds(),
                Set.of("order:query", "order:cancel", "knowledge:search")
        );
    }

    public ToolExecutionContext(
            String requestId,
            String tenantId,
            String userId,
            String department,
            Set<String> departmentIds,
            int permissionLevel,
            Set<String> roles,
            Set<String> permissions
    ) {
        this.requestId = requestId;
        this.tenantId = tenantId;
        this.userId = userId;
        this.department = department;
        this.departmentIds = departmentIds == null ? Set.of() : Set.copyOf(departmentIds);
        this.permissionLevel = permissionLevel;
        this.roles = roles == null ? Set.of() : Set.copyOf(roles);
        this.permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public Set<String> getDepartmentIds() {
        return departmentIds;
    }

    public void setDepartmentIds(Set<String> departmentIds) {
        this.departmentIds = departmentIds == null ? Set.of() : Set.copyOf(departmentIds);
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
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

    public int getPermissionLevel() {
        return permissionLevel;
    }

    public void setPermissionLevel(int permissionLevel) {
        this.permissionLevel = permissionLevel;
    }

    public Set<String> getRoles() {
        return roles;
    }

    public void setRoles(Set<String> roles) {
        this.roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    public Set<String> getPermissions() {
        return permissions;
    }

    public void setPermissions(Set<String> permissions) {
        this.permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    public boolean isConfirmedHighRiskExecution() {
        return confirmedHighRiskExecution;
    }

    public void setConfirmedHighRiskExecution(boolean confirmedHighRiskExecution) {
        this.confirmedHighRiskExecution = confirmedHighRiskExecution;
    }
}
