package com.smartparking.workflow;

public record WorkflowEvent(long sequence, String operationId, String key, String message) { }
