package com.yhl.rag.security;

public class CurrentUser {

    private String userId;

    private String department;

    private int permissionLevel;

    public CurrentUser() {
    }

    public CurrentUser(String userId, String department, int permissionLevel) {
        this.userId = userId;
        this.department = department;
        this.permissionLevel = permissionLevel;
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
