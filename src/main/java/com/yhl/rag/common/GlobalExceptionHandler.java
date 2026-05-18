package com.yhl.rag.common;

import java.time.Instant;
import java.util.Map;

import com.yhl.rag.llm.LlmException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(LlmException.class)
    public ResponseEntity<Map<String, Object>> handleLlmException(LlmException exception) {
        HttpStatus status = exception.getErrorType().name().equals("API_KEY_MISSING")
                || exception.getErrorType().name().equals("INPUT_TOO_LONG")
                ? HttpStatus.BAD_REQUEST
                : HttpStatus.BAD_GATEWAY;

        return ResponseEntity
                .status(status)
                .body(Map.of(
                        "timestamp", Instant.now().toString(),
                        "errorType", exception.getErrorType().name(),
                        "message", exception.getMessage()
                ));
    }
}
