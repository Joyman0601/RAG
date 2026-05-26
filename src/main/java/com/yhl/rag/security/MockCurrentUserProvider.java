package com.yhl.rag.security;

import java.util.Set;

import org.springframework.stereotype.Component;

@Component
public class MockCurrentUserProvider {

    public CurrentUser getCurrentUser() {
        return new CurrentUser(
                "tenant-default",
                "user_001",
                "default-department",
                Set.of("default-department"),
                Set.of("customer"),
                1
        );
    }
}
