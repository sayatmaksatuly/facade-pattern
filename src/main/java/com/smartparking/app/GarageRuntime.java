package com.smartparking.app;

import com.smartparking.facade.AdministrationFacade;
import com.smartparking.facade.ParkingFacade;
import com.smartparking.gate.GateController;
import com.smartparking.gate.GateGateway;
import com.smartparking.gate.LicensePlateReader;
import com.smartparking.infrastructure.Database;
import com.smartparking.payment.OverstayAlert;
import com.smartparking.payment.PaymentGateway;
import com.smartparking.payment.PaymentTerminal;
import com.smartparking.spaces.ReservationGateway;
import com.smartparking.spaces.ReservationManager;
import com.smartparking.spaces.SpaceSensorGrid;
import com.smartparking.workflow.WorkflowStore;
import java.nio.file.Path;

/** Composition root: this is the only application class that constructs internal subsystem objects. */
public final class GarageRuntime implements AutoCloseable {
    private final Database database;
    private final ParkingFacade parking;
    private final AdministrationFacade administration;

    public GarageRuntime(Path path) {
        database = new Database(path);
        try {
            var gate = new GateController(new GateGateway(database), new LicensePlateReader());
            var spaces = new ReservationManager(new ReservationGateway(database), new SpaceSensorGrid());
            var payments = new PaymentTerminal(new PaymentGateway(database), new OverstayAlert());
            var workflows = new WorkflowStore(database);
            parking = new ParkingFacade(gate, spaces, payments, workflows);
            administration = new AdministrationFacade(gate, spaces, payments, workflows, parking);
        } catch (RuntimeException failure) {
            database.close();
            throw failure;
        }
    }

    public ParkingFacade parking() { return parking; }
    public AdministrationFacade administration() { return administration; }
    @Override public void close() { database.close(); }
}
