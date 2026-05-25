package com.yhl.rag.tool;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ToolController.class)
public class ToolExceptionHandler {

    @ExceptionHandler(ToolException.class)
    public ResponseEntity<ToolResult> handleToolException(ToolException exception) {
        return ResponseEntity
                .status(exception.getHttpStatus())
                .body(ToolResult.failure(exception.getToolName(), exception.getErrorType(), exception.getMessage(), 0));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ToolResult> handleHttpMessageNotReadable(HttpMessageNotReadableException exception) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ToolResult.failure(null, "TOOL_REQUEST_INVALID", "request body is not valid JSON", 0));
    }
}
