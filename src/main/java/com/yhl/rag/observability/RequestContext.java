package com.yhl.rag.observability;

import org.slf4j.MDC;
import org.springframework.util.StringUtils;

public final class RequestContext {

    public static final String REQUEST_ID_KEY = "requestId";

    private static final ThreadLocal<String> ERROR_CODE = new ThreadLocal<>();

    private RequestContext() {
    }

    public static String requestId() {
        return MDC.get(REQUEST_ID_KEY);
    }

    public static String requestIdOr(String fallback) {
        String requestId = requestId();
        return StringUtils.hasText(requestId) ? requestId : fallback;
    }

    public static void setErrorCode(String errorCode) {
        ERROR_CODE.set(errorCode);
    }

    public static String errorCode() {
        return ERROR_CODE.get();
    }

    public static void clear() {
        ERROR_CODE.remove();
        MDC.remove(REQUEST_ID_KEY);
    }
}
