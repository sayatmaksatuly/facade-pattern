package com.smartparking.spaces;

import com.smartparking.infrastructure.DomainException;

/** Allocation lifecycle is enforced here even when no facade is involved. */
public record ParkingReservation(String operationId, int space, Status status) {
    public enum Status { RESERVED, OCCUPIED, RELEASED }

    public ParkingReservation {
        if (operationId == null || !operationId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
                || space < 1 || space > 3 || status == null) {
            throw new DomainException("A reservation requires a valid operation ID, space 1-3 and status.");
        }
    }

    public ParkingReservation occupy() {
        if (status != Status.RESERVED) {
            throw new DomainException("Only a RESERVED space can become OCCUPIED; current status: " + status);
        }
        return new ParkingReservation(operationId, space, Status.OCCUPIED);
    }

    public ParkingReservation release() {
        if (status == Status.RELEASED) {
            throw new DomainException("The reservation is already RELEASED.");
        }
        return new ParkingReservation(operationId, space, Status.RELEASED);
    }
}
