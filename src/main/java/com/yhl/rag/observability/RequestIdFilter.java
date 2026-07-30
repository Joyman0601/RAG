package com.yhl.rag.observability;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

import com.yhl.rag.security.CurrentUser;
import com.yhl.rag.security.MockCurrentUserProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    private final MockCurrentUserProvider currentUserProvider;

    private final MetricsService metricsService;

    private final AlertRuleService alertRuleService;

    public RequestIdFilter(
            MockCurrentUserProvider currentUserProvider,
            MetricsService metricsService,
            AlertRuleService alertRuleService
    ) {
        this.currentUserProvider = currentUserProvider;
        this.metricsService = metricsService;
        this.alertRuleService = alertRuleService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long startedAt = System.nanoTime();
        String requestId = StringUtils.hasText(request.getHeader(REQUEST_ID_HEADER))
                ? request.getHeader(REQUEST_ID_HEADER)
                : UUID.randomUUID().toString();
        MDC.put(RequestContext.REQUEST_ID_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            int status = response.getStatus();
            metricsService.recordApiRequest(status);
            CurrentUser currentUser = currentUserProvider.getCurrentUser();
            String errorCode = RequestContext.errorCode();
            log.info("api_request path={} method={} tenantId={} userId={} latencyMs={} status={} errorCode={}",
                    request.getRequestURI(),
                    request.getMethod(),
                    currentUser.getTenantId(),
                    currentUser.getUserId(),
                    Duration.ofNanos(System.nanoTime() - startedAt).toMillis(),
                    status,
                    errorCode);
            alertRuleService.evaluate(metricsService.snapshot());
            RequestContext.clear();
        }
    }
}
