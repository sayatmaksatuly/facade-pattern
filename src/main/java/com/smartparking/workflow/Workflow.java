package com.smartparking.workflow;

public record Workflow(ParkingRequest request, WorkflowState state, String lastError) { }
