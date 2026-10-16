package com.smartparking.workflow;

public enum WorkflowState {
    PENDING_A, PENDING_B, PENDING_C, COMPLETED, COMPENSATING, COMPENSATED, REJECTED;

    public boolean terminal() {
        return this == COMPLETED || this == COMPENSATED || this == REJECTED;
    }
}
