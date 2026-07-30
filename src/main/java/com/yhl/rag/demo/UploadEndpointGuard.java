package com.yhl.rag.demo;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class UploadEndpointGuard implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(UploadEndpointGuard.class);

    private static final String[] WRITE_PATTERNS = {
            "/api/documents/upload",
            "/api/documents/*",
            "/api/rag/documents"
    };

    private final DemoProperties demoProperties;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public UploadEndpointGuard(DemoProperties demoProperties) {
        this.demoProperties = demoProperties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {

        if (Boolean.TRUE.equals(demoProperties.uploadEnabled())) {
            return true;
        }

        String method = request.getMethod();
        if (!"POST".equalsIgnoreCase(method) && !"PUT".equalsIgnoreCase(method) && !"DELETE".equalsIgnoreCase(method)) {
            return true;
        }

        String path = request.getRequestURI();
        boolean matched = false;
        for (String pattern : WRITE_PATTERNS) {
            if (pathMatcher.match(pattern, path)) {
                matched = true;
                break;
            }
        }
        if (!matched) {
            return true;
        }

        log.warn("upload_endpoint_blocked path={} method={} ip={}", path, method, request.getRemoteAddr());
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"error\":\"upload_disabled\",\"message\":\"演示环境为只读知识库，上传/修改/删除已禁用\"}"
        );
        return false;
    }
}
