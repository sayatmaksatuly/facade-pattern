package com.smartparking.gate;

import com.smartparking.infrastructure.Database;
import com.smartparking.infrastructure.DomainException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class GateControllerTest {
    @TempDir Path directory;
    private Database database;
    private GateController gate;

    @BeforeEach void openAnEmptyGarage() { reopenGate(); }
    @AfterEach void closeGarage() { database.close(); }

    private void reopenGate() {
        database = new Database(directory.resolve("gate.db"));
        gate = new GateController(new GateGateway(database), new LicensePlateReader());
    }

    private GateAdmission aDriverHasAnEntryPermit() { return gate.hold("visit-1", " ab-123 ", 180); }

    @Test void aStandaloneGateNormalizesPlatesAndPersistsTheFullLifecycle() {
        assertEquals("AB-123", aDriverHasAnEntryPermit().plate());
        assertEquals(GateAdmission.Status.HELD, gate.find("visit-1").orElseThrow().status());
        gate.admit("visit-1");
        gate.admit("visit-1");
        assertEquals(GateAdmission.Status.ADMITTED, gate.find("visit-1").orElseThrow().status());
        gate.cancel("visit-1");
        assertEquals(GateAdmission.Status.CANCELLED, gate.all().get(0).status());
    }

    @Test void repeatingTheSameRequestDoesNotCreateAnotherPermit() {
        var permit = aDriverHasAnEntryPermit();
        assertEquals(permit, gate.hold("visit-1", "AB-123", 180));
        gate.admit("visit-1");
        assertEquals(GateAdmission.Status.ADMITTED, gate.hold("visit-1", "AB-123", 180).status());
        assertEquals(1, gate.all().size());
    }

    @Test void anOperationIdCannotBeReassignedToAnotherVehicleOrHeight() {
        var permit = aDriverHasAnEntryPermit();
        assertThrows(DomainException.class, () -> gate.hold("visit-1", "OTHER", 180));
        assertThrows(DomainException.class, () -> gate.hold("visit-1", "AB-123", 190));
        assertEquals(permit, gate.find("visit-1").orElseThrow());
    }

    @Test void repeatedCompensationIsSafeButCancelledPermitsCannotBeReused() {
        aDriverHasAnEntryPermit();
        gate.cancel("visit-1");
        gate.cancel("visit-1");
        gate.cancel("missing");
        assertThrows(DomainException.class, () -> gate.hold("visit-1", "AB-123", 180));
        assertThrows(DomainException.class, () -> gate.admit("visit-1"));
        assertEquals(GateAdmission.Status.CANCELLED, gate.find("visit-1").orElseThrow().status());
    }

    @Test void anOverheightVehicleFailsBeforeAnyPermitIsStored() {
        assertThrows(DomainException.class, () -> gate.hold("tall", "BUS-1", 221));
        assertThrows(DomainException.class, () -> gate.hold("zero", "CAR-1", 0));
        assertTrue(gate.all().isEmpty());
        assertEquals(220, gate.hold("limit", "CAR-1", 220).heightCm());
    }

    @Test void malformedInputsAreRejectedWithoutPersistingAnything() {
        assertThrows(DomainException.class, () -> gate.hold("one", null, 180));
        assertThrows(DomainException.class, () -> gate.hold("two", "?!", 180));
        assertThrows(DomainException.class, () -> gate.hold(null, "AB-123", 180));
        assertThrows(DomainException.class, () -> gate.hold(" ", "AB-123", 180));
        assertThrows(DomainException.class, () -> gate.admit("unknown"));
        assertTrue(gate.find("unknown").isEmpty());
        assertTrue(gate.all().isEmpty());
    }

    @Test void entityTransitionsAreEnforcedWithoutAnyControllerOrFacade() {
        var held = new GateAdmission("id", "AA-111", 180, GateAdmission.Status.HELD);
        var admitted = held.admit();
        var cancelled = held.cancel();
        assertThrows(DomainException.class, admitted::admit);
        assertThrows(DomainException.class, cancelled::admit);
        assertThrows(DomainException.class, cancelled::cancel);
        assertEquals(GateAdmission.Status.CANCELLED, admitted.cancel().status());
        assertEquals(GateAdmission.Status.HELD, held.status(), "Transitions must not mutate earlier snapshots");
    }

    @Test void entityRejectsInvalidRestoredData() {
        assertThrows(DomainException.class, () -> new GateAdmission("id", null, 180, GateAdmission.Status.HELD));
        assertThrows(DomainException.class, () -> new GateAdmission("id", "?", 180, GateAdmission.Status.HELD));
        assertThrows(DomainException.class, () -> new GateAdmission("id", "AB-123", 0, GateAdmission.Status.HELD));
        assertThrows(DomainException.class, () -> new GateAdmission("id", "AB-123", 221, GateAdmission.Status.HELD));
        assertThrows(DomainException.class, () -> new GateAdmission("id", "AB-123", 180, null));
    }

    @Test void anEntryPermitSurvivesClosingAndReopeningItsStore() {
        var permit = aDriverHasAnEntryPermit();
        database.close();
        reopenGate();
        assertEquals(permit, gate.find("visit-1").orElseThrow());
        gate.cancel("visit-1");
        database.close();
        reopenGate();
        assertEquals(GateAdmission.Status.CANCELLED, gate.find("visit-1").orElseThrow().status());
    }
}
