package com.smartparking.payment;

import com.smartparking.infrastructure.DomainException;

/** Amounts are integer cents; immutable transitions prevent accidental resurrection. */
public record ParkingPayment(String operationId, int hours, int tenderCents, int amountCents, Status status) {
    public enum Status { AUTHORIZED, CAPTURED, REFUNDED }

    public ParkingPayment {
        if (operationId == null || !operationId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
                || hours < 1 || hours > 24 || amountCents != hours * 500 || tenderCents < amountCents || status == null) {
            throw new DomainException("Payment requires a valid ID, 1-24 prepaid hours and sufficient tender.");
        }
    }

    public ParkingPayment capture() {
        if (status != Status.AUTHORIZED) {
            throw new DomainException("Only an AUTHORIZED payment can be CAPTURED; current status: " + status);
        }
        return new ParkingPayment(operationId, hours, tenderCents, amountCents, Status.CAPTURED);
    }

    public ParkingPayment refund() {
        if (status == Status.REFUNDED) {
            throw new DomainException("The payment is already REFUNDED.");
        }
        return new ParkingPayment(operationId, hours, tenderCents, amountCents, Status.REFUNDED);
    }
}
