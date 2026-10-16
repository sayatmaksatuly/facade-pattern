package com.smartparking.spaces;

import com.smartparking.infrastructure.DomainException;

/** Pure sensor decision policy; persisted readings are supplied by the gateway. */
public final class SpaceSensorGrid {
    public void validateSpace(int space) {
        if (space < 1 || space > 3) {
            throw new DomainException("Parking space must be between 1 and 3.");
        }
    }

    public void requireAvailable(int space, boolean blocked, boolean allocated) {
        validateSpace(space);
        if (blocked) {
            throw new DomainException("Space " + space + " is blocked by a sensor obstruction.");
        }
        if (allocated) {
            throw new DomainException("Space " + space + " already has an active reservation.");
        }
    }
}
