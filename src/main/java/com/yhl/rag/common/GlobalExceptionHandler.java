package com.yhl.rag.common;

import java.time.Instant;
import java.util.Map;

import com.yhl.rag.cost.CostException;
import com.yhl.rag.document.DocumentException;
import com.yhl.rag.llm.LlmException;
import com.yhl.rag.observability.RequestContext;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CostException.class)
    public ResponseEntity<Map<String, Object>> handleCostException(CostException exception) {
        RequestContext.setErrorCode(exception.getErrorCode().name());
        HttpStatus status = "RATE_LIMITED".equals(exception.getErrorCode().name())
                ? HttpStatus.TOO_MANY_REQUESTS
                : HttpStatus.BAD_REQUEST;

        return ResponseEntity
                .status(status)
                .body(Map.of(
                        "timestamp", Instant.now().toString(),
                        "errorType", exception.getErrorCode().name(),
                        "message", exception.getMessage()
                ));
    }

    @ExceptionHandler(LlmException.class)
    public ResponseEntity<Map<String, Object>> handleLlmException(LlmException exception) {
        RequestContext.setErrorCode(exception.getErrorType().name());
        HttpStatus status = exception.getErrorType().name().equals("API_KEY_MISSING")
                || exception.getErrorType().name().equals("EMBEDDING_CONFIG_MISSING")
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

    @ExceptionHandler(DocumentException.class)
    public ResponseEntity<Map<String, Object>> handleDocumentException(DocumentException exception) {
        RequestContext.setErrorCode(exception.getErrorType());
        HttpStatus status = "DOCUMENT_NOT_FOUND".equals(exception.getErrorType())
                ? HttpStatus.NOT_FOUND
                : HttpStatus.BAD_REQUEST;

        return ResponseEntity
                .status(status)
                .body(Map.of(
                        "timestamp", Instant.now().toString(),
                        "errorType", exception.getErrorType(),
                        "message", exception.getMessage()
                ));
    }

    @ExceptionHandler({ConstraintViolationException.class, MissingServletRequestPartException.class})
    public ResponseEntity<Map<String, Object>> handleValidationException(Exception exception) {
        RequestContext.setErrorCode("VALIDATION_ERROR");
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "timestamp", Instant.now().toString(),
                        "errorType", "VALIDATION_ERROR",
                        "message", exception.getMessage()
                ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgumentException(IllegalArgumentException exception) {
        RequestContext.setErrorCode("VALIDATION_ERROR");
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "timestamp", Instant.now().toString(),
                        "errorType", "VALIDATION_ERROR",
                        "message", exception.getMessage()
                ));
    }
}
