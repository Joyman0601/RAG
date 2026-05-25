package com.yhl.rag.tool;

import com.yhl.rag.security.CurrentUser;

public class ToolExecutionContext {

    private String requestId;

    private String userId;

    private String department;

    private int permissionLevel;

    public ToolExecutionContext() {
    }

    public ToolExecutionContext(String requestId, String userId, String department, int permissionLevel) {
        this.requestId = requestId;
        this.userId = userId;
        this.department = department;
        this.permissionLevel = permissionLevel;
    }

    public static ToolExecutionContext from(String requestId, CurrentUser currentUser) {
        return new ToolExecutionContext(
                requestId,
                currentUser.getUserId(),
                currentUser.getDepartment(),
                currentUser.getPermissionLevel()
        );
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
}
