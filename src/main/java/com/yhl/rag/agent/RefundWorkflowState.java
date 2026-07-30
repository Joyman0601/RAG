package com.yhl.rag.agent;

public enum RefundWorkflowState {
    INIT,
    NEED_ORDER_ID,
    ORDER_READY,
    ORDER_QUERIED,
    POLICY_CHECKED,
    WAITING_CONFIRMATION,
    SUBMITTED,
    REJECTED,
    CANCELLED,
    FAILED,
    DONE
}
