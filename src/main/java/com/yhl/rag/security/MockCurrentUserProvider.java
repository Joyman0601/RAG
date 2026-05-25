package com.yhl.rag.security;

import org.springframework.stereotype.Component;

@Component
public class MockCurrentUserProvider {

    public CurrentUser getCurrentUser() {
        return new CurrentUser("mock-user-1", "default-department", 1);
    }
}
