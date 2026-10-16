package com.smartparking.gate;

import com.smartparking.infrastructure.DomainException;

/** A durable entry permit. Cancelling a permit preserves its audit record. */
public record GateAdmission(String operationId, String plate, int heightCm, Status status) {
    public enum Status { HELD, ADMITTED, CANCELLED }

    public GateAdmission {
        if (operationId == null || !operationId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new DomainException("Operation ID must contain 1-64 letters, digits, dots, underscores or hyphens.");
        }
        if (plate == null || !plate.matches("[A-Z0-9][A-Z0-9-]{1,11}") || heightCm < 1 || heightCm > 220 || status == null) {
            throw new DomainException("A gate admission requires a valid plate, height and status.");
        }
    }

    public GateAdmission admit() {
        if (status != Status.HELD) {
            throw new DomainException("Only a HELD gate permit can be admitted; current status: " + status);
        }
        return new GateAdmission(operationId, plate, heightCm, Status.ADMITTED);
    }

    public GateAdmission cancel() {
        if (status == Status.CANCELLED) {
            throw new DomainException("The gate permit is already CANCELLED.");
        }
        return new GateAdmission(operationId, plate, heightCm, Status.CANCELLED);
    }
}
