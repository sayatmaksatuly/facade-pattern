package com.smartparking.facade;

import com.smartparking.gate.GateController;
import com.smartparking.infrastructure.DomainException;
import com.smartparking.payment.PaymentTerminal;
import com.smartparking.spaces.ReservationManager;
import com.smartparking.workflow.ParkingRequest;
import com.smartparking.workflow.Workflow;
import com.smartparking.workflow.WorkflowState;
import com.smartparking.workflow.WorkflowStore;

/** Coordinates independent, already committed operations as a durable compensating workflow. */
public final class ParkingFacade {
    private final GateController gate;
    private final ReservationManager spaces;
    private final PaymentTerminal payments;
    private final WorkflowStore workflows;

    public ParkingFacade(GateController gate, ReservationManager spaces,
                         PaymentTerminal payments, WorkflowStore workflows) {
        this.gate = gate;
        this.spaces = spaces;
        this.payments = payments;
        this.workflows = workflows;
    }

    /** The normal client's single operation. Reusing the same request is safe. */
    public Workflow park(ParkingRequest request) {
        begin(request);
        return resume(request.operationId());
    }

    private Workflow begin(ParkingRequest request) {
        String id = request.operationId();
        if (workflows.find(id).isEmpty()
                && (gate.find(id).isPresent() || spaces.find(id).isPresent() || payments.find(id).isPresent())) {
            throw new DomainException("Operation ID already belongs to standalone subsystem work. Choose a new ID.");
        }
        return workflows.createOrLoad(request);
    }

    /** Read progress from disk on every iteration; do not infer commitment from local flags. */
    public Workflow resume(String id) {
        while (true) {
            Workflow current = workflows.get(id);
            if (current.state().terminal()) return current;
            if (current.state() == WorkflowState.COMPENSATING) return compensate(id, current.lastError());
            try {
                advance(current);
            } catch (RuntimeException failure) {
                return handleFailure(id, current.state(), failure);
            }
        }
    }

    /** An explicit durable checkpoint used to demonstrate interruption after A. */
    public Workflow prepareAfterA(ParkingRequest request) {
        Workflow current = begin(request);
        if (current.state() == WorkflowState.PENDING_B) return current;
        if (current.state() != WorkflowState.PENDING_A) {
            throw new DomainException("Checkpoint requires a new operation or an existing PENDING_B operation.");
        }
        try { advance(current); }
        catch (RuntimeException failure) { return handleFailure(request.operationId(), current.state(), failure); }
        return workflows.get(request.operationId());
    }

    private void advance(Workflow current) {
        ParkingRequest request = current.request();
        String id = request.operationId();
        switch (current.state()) {
            case PENDING_A -> {
                workflows.event(id, "A_STARTED", "Stage A executing: read plate and hold entry clearance.");
                gate.hold(id, request.plate(), request.heightCm());
                workflows.transition(id, WorkflowState.PENDING_B, "", "A_COMMITTED",
                        "A committed: entry clearance HELD; next B.");
            }
            case PENDING_B -> {
                workflows.event(id, "B_STARTED", "Stage B executing: check sensor and reserve space.");
                spaces.reserve(id, request.space());
                workflows.transition(id, WorkflowState.PENDING_C, "", "B_COMMITTED",
                        "B committed: space RESERVED; next C.");
            }
            case PENDING_C -> {
                workflows.event(id, "C_STARTED", "Stage C executing: calculate parking charge and validate tender.");
                payments.charge(id, request.hours(), request.tenderCents());
                workflows.transition(id, WorkflowState.COMPLETED, "", "C_COMMITTED",
                        "C committed: payment CAPTURED; workflow COMPLETED.");
            }
            default -> throw new IllegalStateException("Cannot advance " + current.state());
        }
    }

    private Workflow handleFailure(String id, WorkflowState stage, RuntimeException failure) {
        String reason = stage + ": " + failure.getClass().getSimpleName() + ": " + failure.getMessage();
        // Even if a gateway threw after committing, inspect its durable effect before deciding what to undo.
        if (gate.find(id).isEmpty() && spaces.find(id).isEmpty() && payments.find(id).isEmpty()) {
            workflows.transition(id, WorkflowState.REJECTED, reason, "REJECTED",
                    "Failure before any domain commit: " + reason);
            return workflows.get(id);
        }
        return compensate(id, reason);
    }

    /** Compensation intent is durable before any undo. Each action is idempotent and separately committed. */
    public Workflow compensate(String id, String reason) {
        Workflow current = workflows.get(id);
        if (current.state() == WorkflowState.COMPENSATED || current.state() == WorkflowState.REJECTED) return current;
        String retainedReason = current.state() == WorkflowState.COMPENSATING ? current.lastError() : reason;
        workflows.transition(id, WorkflowState.COMPENSATING, retainedReason, "COMPENSATING",
                "Failure or administrative cancellation: " + retainedReason + ". Starting reverse compensation.");
        try {
            // C can exist after an interrupted journal update or during manual cancellation of a completed visit.
            if (payments.find(id).isPresent()) {
                payments.refund(id);
                workflows.event(id, "C_COMPENSATED", "Compensation C committed: payment REFUNDED.");
            }
            if (spaces.find(id).isPresent()) {
                spaces.release(id);
                workflows.event(id, "B_COMPENSATED", "Compensation B committed: space RELEASED.");
            }
            if (gate.find(id).isPresent()) {
                gate.cancel(id);
                workflows.event(id, "A_COMPENSATED", "Compensation A committed: entry clearance CANCELLED.");
            }
            workflows.transition(id, WorkflowState.COMPENSATED, retainedReason, "COMPENSATED",
                    "Workflow COMPENSATED; all recorded domain effects have been reversed.");
        } catch (RuntimeException failure) {
            // Stop here: never release A if undoing B has failed. Retrying begins with persisted entity states.
            workflows.event(id, "RECOVERY_REQUIRED", "Compensation interrupted: " + failure.getMessage()
                    + ". State remains COMPENSATING; retry administrative recovery.");
        }
        return workflows.get(id);
    }
}
