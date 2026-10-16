package com.smartparking.spaces;

import java.util.List;
import java.util.Optional;

public final class ReservationManager {
    private final ReservationGateway gateway;
    private final SpaceSensorGrid sensorGrid;

    public ReservationManager(ReservationGateway gateway, SpaceSensorGrid sensorGrid) {
        this.gateway = gateway;
        this.sensorGrid = sensorGrid;
    }

    public ParkingReservation reserve(String operationId, int space) {
        sensorGrid.validateSpace(space);
        return gateway.reserve(new ParkingReservation(operationId, space, ParkingReservation.Status.RESERVED), sensorGrid);
    }

    public void occupy(String operationId) { gateway.occupy(operationId); }
    public void release(String operationId) { gateway.release(operationId); }
    public Optional<ParkingReservation> find(String operationId) { return gateway.find(operationId); }
    public List<ParkingReservation> all() { return gateway.all(); }

    public void setSpaceBlocked(int space, boolean blocked) {
        sensorGrid.validateSpace(space);
        gateway.setSpaceBlocked(space, blocked);
    }

    public boolean isSpaceBlocked(int space) {
        sensorGrid.validateSpace(space);
        return gateway.isSpaceBlocked(space);
    }
}
