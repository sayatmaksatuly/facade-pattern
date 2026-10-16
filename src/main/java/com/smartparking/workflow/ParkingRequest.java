package com.smartparking.workflow;

import com.smartparking.infrastructure.DomainException;
import java.util.Locale;

/** Immutable client input and durable idempotency key. Monetary amounts are integer cents. */
public record ParkingRequest(String operationId, String plate, int heightCm,
                             int space, int hours, int tenderCents) {
    public ParkingRequest {
        if (operationId == null || !operationId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
            throw new DomainException("Operation ID must be 1-64 letters, digits, underscores or hyphens.");
        }
        plate = plate == null ? "" : plate.trim().toUpperCase(Locale.ROOT);
    }
}
