package com.yhl.rag.demo;

import java.io.IOException;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class DemoTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(DemoTokenFilter.class);

    private static final String HEADER = "X-Demo-Token";
    private static final String QUERY_PARAM = "token";

    private static final Set<String> BYPASS_PATTERNS = Set.of(
            "/error",
            "/favicon.ico"
    );

    private final DemoProperties demoProperties;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public DemoTokenFilter(DemoProperties demoProperties) {
        this.demoProperties = demoProperties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (!demoProperties.isTokenEnforced()) {
            filterChain.doFilter(request, response);
            return;
        }

        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        for (String pattern : BYPASS_PATTERNS) {
            if (pathMatcher.match(pattern, path)) {
                filterChain.doFilter(request, response);
                return;
            }
        }

        String supplied = request.getHeader(HEADER);
        if (!StringUtils.hasText(supplied)) {
            supplied = request.getParameter(QUERY_PARAM);
        }

        if (!demoProperties.token().equals(supplied)) {
            log.warn("demo_token_rejected path={} ip={} supplied={}",
                    path,
                    request.getRemoteAddr(),
                    StringUtils.hasText(supplied) ? "***" : "<empty>");
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"error\":\"unauthorized\",\"message\":\"缺少或无效的 X-Demo-Token（面试演示环境需要 token）\"}"
            );
            return;
        }

        filterChain.doFilter(request, response);
    }
}
