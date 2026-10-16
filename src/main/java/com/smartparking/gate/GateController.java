package com.smartparking.gate;

import java.util.List;
import java.util.Optional;

/** Standalone gate API; knows only its own policy, entity and persistence. */
public final class GateController {
    private final GateGateway gateway;
    private final LicensePlateReader reader;

    public GateController(GateGateway gateway, LicensePlateReader reader) {
        this.gateway = gateway;
        this.reader = reader;
    }

    public GateAdmission hold(String operationId, String plate, int heightCm) {
        return gateway.hold(new GateAdmission(operationId, reader.read(plate, heightCm),
                heightCm, GateAdmission.Status.HELD));
    }

    public void admit(String operationId) { gateway.admit(operationId); }
    public void cancel(String operationId) { gateway.cancel(operationId); }
    public Optional<GateAdmission> find(String operationId) { return gateway.find(operationId); }
    public List<GateAdmission> all() { return gateway.all(); }
}
