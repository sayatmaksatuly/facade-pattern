package com.smartparking.facade;

import com.smartparking.gate.GateController;
import com.smartparking.infrastructure.DomainException;
import com.smartparking.payment.PaymentTerminal;
import com.smartparking.spaces.ReservationManager;
import com.smartparking.workflow.ParkingRequest;
import com.smartparking.workflow.Workflow;
import com.smartparking.workflow.WorkflowStore;
import java.util.ArrayList;
import java.util.List;

/** Narrow operational surface: inspection, recovery, sensor maintenance and isolated subsystem demonstrations. */
public final class AdministrationFacade {
    private final GateController gate;
    private final ReservationManager spaces;
    private final PaymentTerminal payments;
    private final WorkflowStore workflows;
    private final ParkingFacade parking;

    public AdministrationFacade(GateController gate, ReservationManager spaces, PaymentTerminal payments,
                                WorkflowStore workflows, ParkingFacade parking) {
        this.gate = gate; this.spaces = spaces; this.payments = payments;
        this.workflows = workflows; this.parking = parking;
    }

    public String inspect() {
        StringBuilder report = new StringBuilder("=== Persisted SQLite state ===\nA - Gate admissions\n");
        gate.all().forEach(record -> report.append(record).append('\n'));
        report.append("B - Parking reservations\n");
        spaces.all().forEach(record -> report.append(record).append('\n'));
        for (int space = 1; space <= 3; space++) {
            report.append("Sensor space ").append(space).append(": ")
                    .append(spaces.isSpaceBlocked(space) ? "BLOCKED" : "CLEAR").append('\n');
        }
        report.append("C - Parking payments (amounts in cents)\n");
        payments.all().forEach(record -> report.append(record).append('\n'));
        report.append("Workflow progress\n");
        workflows.all().forEach(record -> report.append(record).append('\n'));
        return report.toString();
    }

    public String history(String id) {
        Workflow current = workflows.get(id);
        StringBuilder report = new StringBuilder("Operation " + id + "\n");
        workflows.events(id).forEach(event -> report.append(event.sequence()).append(". ")
                .append(event.message()).append('\n'));
        report.append("Final persisted state: ").append(current.state()).append('\n');
        if (!current.lastError().isEmpty()) report.append("Reason: ").append(current.lastError()).append('\n');
        return report.toString();
    }

    public List<String> incompleteOperations() {
        return workflows.all().stream().filter(w -> !w.state().terminal())
                .map(w -> w.request().operationId()).toList();
    }

    /** Independent requests continue recovering even if one has a storage or business problem. */
    public List<String> recoverAll() {
        List<String> results = new ArrayList<>();
        for (String id : incompleteOperations()) {
            try { results.add(id + " -> " + parking.resume(id).state()); }
            catch (RuntimeException failure) { results.add(id + " -> recovery unavailable: " + failure.getMessage()); }
        }
        return List.copyOf(results);
    }

    public Workflow recover(String id) { return parking.resume(id); }

    public Workflow cancel(String id) { return parking.compensate(id, "Administrator requested cancellation"); }

    public void setSpaceBlocked(int space, boolean blocked) { spaces.setSpaceBlocked(space, blocked); }

    public Workflow checkpoint(ParkingRequest request) { return parking.prepareAfterA(request); }

    /** Each branch exercises just one subsystem; no ParkingFacade workflow or cross-subsystem entity is needed. */
    public String demonstrateStandalone(String subsystem, String id) {
        if (workflows.find(id).isPresent()) {
            throw new DomainException("Operation ID belongs to a workflow; standalone changes are not allowed.");
        }
        switch (subsystem.toLowerCase(java.util.Locale.ROOT)) {
            case "gate" -> {
                gate.hold(id, "SOLO123", 180);
                gate.admit(id);
                gate.cancel(id);
                return "Independent A: HELD -> ADMITTED -> " + gate.find(id).orElseThrow().status();
            }
            case "spaces" -> {
                spaces.reserve(id, 3);
                spaces.occupy(id);
                spaces.release(id);
                return "Independent B: RESERVED -> OCCUPIED -> " + spaces.find(id).orElseThrow().status();
            }
            case "payment" -> {
                payments.charge(id, 2, 1000);
                payments.refund(id);
                return "Independent C: AUTHORIZED -> CAPTURED -> " + payments.find(id).orElseThrow().status();
            }
            default -> throw new DomainException("Choose gate, spaces or payment.");
        }
    }
}
