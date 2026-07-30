package com.yhl.rag.demo;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class LlmQuotaExceptionHandler {

    @ExceptionHandler(LlmQuotaExceededException.class)
    public ResponseEntity<Map<String, Object>> handle(LlmQuotaExceededException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of(
                "error", "demo_quota_exhausted",
                "message", ex.getMessage(),
                "limit", ex.getLimit()
        ));
    }
}
