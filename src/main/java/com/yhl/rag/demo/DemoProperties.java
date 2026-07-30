package com.yhl.rag.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "demo")
public record DemoProperties(
        String token,
        Boolean uploadEnabled,
        Integer maxDailyLlmCalls,
        RateLimit rateLimit
) {

    public DemoProperties {
        if (uploadEnabled == null) uploadEnabled = Boolean.TRUE;
        if (maxDailyLlmCalls == null || maxDailyLlmCalls <= 0) maxDailyLlmCalls = 500;
        if (rateLimit == null) rateLimit = new RateLimit(false, 10);
    }

    public boolean isTokenEnforced() {
        return StringUtils.hasText(token);
    }

    public record RateLimit(Boolean enabled, Integer requestsPerMinute) {
        public RateLimit {
            if (enabled == null) enabled = Boolean.FALSE;
            if (requestsPerMinute == null || requestsPerMinute <= 0) requestsPerMinute = 10;
        }
    }
}
