package com.yhl.rag.tool;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class CancelOrderToolRequest {

    @NotBlank(message = "orderId cannot be blank")
    @Size(max = 64, message = "orderId length must be less than or equal to 64")
    @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "orderId can only contain letters, numbers, hyphen and underscore")
    private String orderId;

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }
}
