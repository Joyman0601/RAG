package com.yhl.rag.cost;

public class CostException extends RuntimeException {

    private final CostErrorCode errorCode;

    public CostException(CostErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public CostErrorCode getErrorCode() {
        return errorCode;
    }
}
