package com.smartparking.facade;

import com.smartparking.gate.*;
import com.smartparking.infrastructure.*;
import com.smartparking.payment.*;
import com.smartparking.spaces.*;
import com.smartparking.workflow.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Sentence-like helpers express parking behavior against a fresh real SQLite store for each test. */
class ParkingFacadeTest {
    @TempDir Path directory;
    private Database database;
    private GateController gate;
    private ReservationManager spaces;
    private PaymentTerminal payments;
    private WorkflowStore journal;
    private ParkingFacade parking;
    private AdministrationFacade admin;

    @BeforeEach void openGarage() {
        database = new Database(directory.resolve("parking.db"));
        gate = new GateController(new GateGateway(database), new LicensePlateReader());
        spaces = new ReservationManager(new ReservationGateway(database), new SpaceSensorGrid());
        payments = new PaymentTerminal(new PaymentGateway(database), new OverstayAlert());
        journal = new WorkflowStore(database);
        parking = new ParkingFacade(gate, spaces, payments, journal);
        admin = new AdministrationFacade(gate, spaces, payments, journal, parking);
    }

    @AfterEach void closeGarage() { database.close(); }

    private ParkingRequest aDriverRequestsParking(String id) { return new ParkingRequest(id, "CAR123", 180, 1, 2, 1000); }
    private void theGarageRestarts() { database.close(); openGarage(); }
    private List<String> eventKeys(String id) { return journal.events(id).stream().map(WorkflowEvent::key).toList(); }
    private void assertBookingIsDurablyConfirmed(String id) {
        assertEquals(WorkflowState.COMPLETED, journal.get(id).state());
        assertEquals(GateAdmission.Status.HELD, gate.find(id).orElseThrow().status());
        assertEquals(ParkingReservation.Status.RESERVED, spaces.find(id).orElseThrow().status());
        assertEquals(ParkingPayment.Status.CAPTURED, payments.find(id).orElseThrow().status());
        assertEquals(1000, payments.find(id).orElseThrow().amountCents());
    }
    private void assertEntryWasCancelled(String id) {
        assertEquals(WorkflowState.COMPENSATED, journal.get(id).state());
        assertEquals(GateAdmission.Status.CANCELLED, gate.find(id).orElseThrow().status());
    }
    private void sql(String sql) {
        database.transaction(c -> { try (var s = c.createStatement()) { s.execute(sql); } return null; });
    }
    private void entryHasCommitted(ParkingRequest request) {
        journal.createOrLoad(request);
        gate.hold(request.operationId(), request.plate(), request.heightCm());
    }
    private void entryAndReservationHaveCommitted(ParkingRequest request) {
        entryHasCommitted(request);
        journal.transition(request.operationId(), WorkflowState.PENDING_B, "", "A_COMMITTED", "A committed");
        spaces.reserve(request.operationId(), request.space());
    }

    @Test void aPaidBookingSurvivesClosingAndReopeningTheDatabase() {
        assertEquals(WorkflowState.COMPLETED, parking.park(aDriverRequestsParking("success")).state());
        theGarageRestarts();
        assertBookingIsDurablyConfirmed("success");
        assertEquals(List.of("REQUESTED", "A_STARTED", "A_COMMITTED", "B_STARTED", "B_COMMITTED", "C_STARTED", "C_COMMITTED"), eventKeys("success"));
    }

    @Test void blockedSpaceFailsAtBAfterEntryCommittedAndCancelsOnlyEntry() {
        admin.setSpaceBlocked(1, true);
        parking.park(aDriverRequestsParking("blocked"));
        theGarageRestarts();
        assertEntryWasCancelled("blocked");
        assertTrue(spaces.find("blocked").isEmpty());
        assertTrue(payments.find("blocked").isEmpty());
        assertTrue(eventKeys("blocked").indexOf("A_COMMITTED") < eventKeys("blocked").indexOf("B_STARTED"));
        assertEquals(List.of("A_COMPENSATED"), eventKeys("blocked").stream().filter(k -> k.endsWith("_COMPENSATED")).toList());
        assertTrue(admin.history("blocked").contains("blocked"));
    }

    @Test void insufficientTenderFailsAtCAfterBothCommitsAndReversesBThenA() {
        parking.park(new ParkingRequest("declined", "PAY123", 180, 1, 2, 100));
        theGarageRestarts();
        assertEntryWasCancelled("declined");
        assertEquals(ParkingReservation.Status.RELEASED, spaces.find("declined").orElseThrow().status());
        assertTrue(payments.find("declined").isEmpty());
        assertEquals(List.of("B_COMPENSATED", "A_COMPENSATED"), eventKeys("declined").stream().filter(k -> k.endsWith("_COMPENSATED")).toList());
        assertTrue(eventKeys("declined").indexOf("B_COMMITTED") < eventKeys("declined").indexOf("C_STARTED"));
        assertTrue(journal.get("declined").lastError().contains("1000 cents required"));
    }

    @Test void anOversizedVehicleIsRejectedBeforeAnyDomainCommit() {
        var result = parking.park(new ParkingRequest("too-high", "BUS123", 260, 1, 2, 1000));
        assertEquals(WorkflowState.REJECTED, result.state());
        assertTrue(gate.all().isEmpty()); assertTrue(spaces.all().isEmpty()); assertTrue(payments.all().isEmpty());
        assertEquals(result, admin.cancel("too-high"));
        assertTrue(admin.history("too-high").contains("Failure before any domain commit"));
    }

    @Test void repeatedIdenticalBookingAndRecoveryDoNotDuplicateSideEffectsOrHistory() {
        var request = aDriverRequestsParking("repeated");
        var original = parking.park(request);
        var keys = eventKeys("repeated");
        assertEquals(original, parking.park(request));
        assertEquals(original, admin.recover("repeated"));
        assertEquals(keys, eventKeys("repeated"));
        assertEquals(1, gate.all().size()); assertEquals(1, spaces.all().size()); assertEquals(1, payments.all().size());
    }

    @Test void operationIdCannotBeReusedForDifferentInput() {
        parking.park(aDriverRequestsParking("same-id"));
        assertThrows(DomainException.class, () -> parking.park(new ParkingRequest("same-id", "CAR123", 180, 2, 2, 1000)));
        assertBookingIsDurablyConfirmed("same-id");
    }

    @Test void aRequestPersistedBeforeAResumesAfterRestart() {
        journal.createOrLoad(aDriverRequestsParking("before-a"));
        theGarageRestarts();
        assertEquals(List.of("before-a"), admin.incompleteOperations());
        assertEquals(List.of("before-a -> COMPLETED"), admin.recoverAll());
        assertBookingIsDurablyConfirmed("before-a");
        assertTrue(admin.incompleteOperations().isEmpty());
    }

    @Test void recoveryReconcilesEntryCommitBeforeItsJournalMarker() {
        entryHasCommitted(aDriverRequestsParking("gap-a"));
        assertEquals(WorkflowState.PENDING_A, journal.get("gap-a").state());
        theGarageRestarts();
        admin.recover("gap-a");
        assertBookingIsDurablyConfirmed("gap-a");
        assertEquals(1, gate.all().size());
    }

    @Test void recoveryReconcilesReservationCommitBeforeItsJournalMarker() {
        entryAndReservationHaveCommitted(aDriverRequestsParking("gap-b"));
        assertEquals(WorkflowState.PENDING_B, journal.get("gap-b").state());
        theGarageRestarts();
        admin.recover("gap-b");
        assertBookingIsDurablyConfirmed("gap-b");
        assertEquals(1, spaces.all().size());
    }

    @Test void recoveryReconcilesPaymentCommitBeforeItsJournalMarkerWithoutChargingTwice() {
        var request = aDriverRequestsParking("gap-c");
        entryAndReservationHaveCommitted(request);
        journal.transition("gap-c", WorkflowState.PENDING_C, "", "B_COMMITTED", "B committed");
        payments.charge("gap-c", 2, 1000);
        theGarageRestarts();
        admin.recover("gap-c");
        assertBookingIsDurablyConfirmed("gap-c");
        assertEquals(1, payments.all().size());
    }

    @Test void reloadedPendingCPaysAttentionToPersistedInputAndCompensatesOnRejection() {
        var request = new ParkingRequest("reload-decline", "CAR123", 180, 1, 2, 100);
        entryAndReservationHaveCommitted(request);
        journal.transition(request.operationId(), WorkflowState.PENDING_C, "", "B_COMMITTED", "B committed");
        theGarageRestarts();
        admin.recover("reload-decline");
        assertEntryWasCancelled("reload-decline");
        assertEquals(ParkingReservation.Status.RELEASED, spaces.find("reload-decline").orElseThrow().status());
    }

    @Test void checkpointIsDurableIdempotentAndCanBeResumed() {
        var request = aDriverRequestsParking("checkpoint");
        assertEquals(WorkflowState.PENDING_B, admin.checkpoint(request).state());
        assertEquals(WorkflowState.PENDING_B, admin.checkpoint(request).state());
        theGarageRestarts();
        assertEquals(WorkflowState.PENDING_B, journal.get("checkpoint").state());
        admin.recover("checkpoint");
        assertBookingIsDurablyConfirmed("checkpoint");
        assertThrows(DomainException.class, () -> admin.checkpoint(request));
    }

    @Test void invalidCheckpointInputIsRecordedAsRejected() {
        assertEquals(WorkflowState.REJECTED, admin.checkpoint(new ParkingRequest("bad-checkpoint", "?", 180, 1, 1, 500)).state());
    }

    @Test void failedBUndoMustNotCancelAUntilRecoverySucceeds() {
        sql("CREATE TRIGGER unavailable_release BEFORE UPDATE ON reservations WHEN NEW.status='RELEASED' BEGIN SELECT RAISE(ABORT, 'Sensor controller unavailable'); END");
        parking.park(new ParkingRequest("undo-b-fails", "CAR123", 180, 1, 2, 100));
        assertEquals(WorkflowState.COMPENSATING, journal.get("undo-b-fails").state());
        assertEquals(GateAdmission.Status.HELD, gate.find("undo-b-fails").orElseThrow().status());
        assertEquals(ParkingReservation.Status.RESERVED, spaces.find("undo-b-fails").orElseThrow().status());
        assertFalse(eventKeys("undo-b-fails").contains("A_COMPENSATED"));
        sql("DROP TRIGGER unavailable_release");
        theGarageRestarts();
        admin.recover("undo-b-fails");
        assertEntryWasCancelled("undo-b-fails");
        assertTrue(eventKeys("undo-b-fails").indexOf("B_COMPENSATED") < eventKeys("undo-b-fails").indexOf("A_COMPENSATED"));
    }

    @Test void aFailureDuringACompensationPreservesReleasedBForRetry() {
        sql("CREATE TRIGGER unavailable_gate BEFORE UPDATE ON gate_admissions WHEN NEW.status='CANCELLED' BEGIN SELECT RAISE(ABORT, 'Gate controller unavailable'); END");
        parking.park(new ParkingRequest("undo-a-fails", "CAR123", 180, 1, 2, 100));
        assertEquals(WorkflowState.COMPENSATING, journal.get("undo-a-fails").state());
        assertEquals(ParkingReservation.Status.RELEASED, spaces.find("undo-a-fails").orElseThrow().status());
        assertEquals(GateAdmission.Status.HELD, gate.find("undo-a-fails").orElseThrow().status());
        sql("DROP TRIGGER unavailable_gate");
        theGarageRestarts();
        admin.recover("undo-a-fails");
        assertEntryWasCancelled("undo-a-fails");
        assertEquals(1, eventKeys("undo-a-fails").stream().filter("B_COMPENSATED"::equals).count());
    }

    @Test void recoveryHandlesCrashAfterBUndoBeforeItsJournalEntry() {
        var request = aDriverRequestsParking("undo-gap");
        entryAndReservationHaveCommitted(request);
        journal.transition("undo-gap", WorkflowState.COMPENSATING, "Cancelled", "COMPENSATING", "Cancelled");
        spaces.release("undo-gap");
        theGarageRestarts();
        admin.recover("undo-gap");
        assertEntryWasCancelled("undo-gap");
        assertEquals(List.of("B_COMPENSATED", "A_COMPENSATED"), eventKeys("undo-gap").stream().filter(k -> k.endsWith("_COMPENSATED")).toList());
    }

    @Test void unexpectedStorageFailureAtBStillCompensatesThePersistedA() {
        sql("CREATE TRIGGER unavailable_allocation BEFORE INSERT ON reservations BEGIN SELECT RAISE(ABORT, 'Controller unavailable'); END");
        parking.park(aDriverRequestsParking("unexpected"));
        assertEntryWasCancelled("unexpected");
        assertTrue(journal.get("unexpected").lastError().contains("StoreException"));
        assertTrue(spaces.find("unexpected").isEmpty());
    }

    @Test void manualCancellationOfCompletedBookingRefundsCThenReleasesBThenCancelsA() {
        parking.park(aDriverRequestsParking("manual"));
        admin.cancel("manual");
        assertEntryWasCancelled("manual");
        assertEquals(ParkingPayment.Status.REFUNDED, payments.find("manual").orElseThrow().status());
        assertEquals(List.of("C_COMPENSATED", "B_COMPENSATED", "A_COMPENSATED"), eventKeys("manual").stream().filter(k -> k.endsWith("_COMPENSATED")).toList());
        var history = eventKeys("manual");
        admin.cancel("manual"); parking.park(aDriverRequestsParking("manual"));
        assertEquals(history, eventKeys("manual"));
    }

    @Test void refundFailureLeavesBothEarlierSubsystemsUntouchedUntilRetry() {
        parking.park(aDriverRequestsParking("refund-fails"));
        sql("CREATE TRIGGER unavailable_refund BEFORE UPDATE ON payments BEGIN SELECT RAISE(ABORT, 'Terminal offline'); END");
        admin.cancel("refund-fails");
        assertEquals(WorkflowState.COMPENSATING, journal.get("refund-fails").state());
        assertEquals(GateAdmission.Status.HELD, gate.find("refund-fails").orElseThrow().status());
        assertEquals(ParkingReservation.Status.RESERVED, spaces.find("refund-fails").orElseThrow().status());
        sql("DROP TRIGGER unavailable_refund");
        theGarageRestarts(); admin.recover("refund-fails");
        assertEntryWasCancelled("refund-fails");
    }

    @Test void administrativeRecoveryContinuesOtherOperationsIfOneJournalCannotBeWritten() {
        journal.createOrLoad(aDriverRequestsParking("unlucky"));
        journal.createOrLoad(new ParkingRequest("lucky", "CAR222", 180, 2, 2, 1000));
        sql("CREATE TRIGGER unavailable_journal BEFORE INSERT ON workflow_events WHEN NEW.operation_id='unlucky' BEGIN SELECT RAISE(ABORT, 'Journal unavailable'); END");
        var outcomes = admin.recoverAll();
        assertTrue(outcomes.get(0).contains("recovery unavailable"));
        assertEquals("lucky -> COMPLETED", outcomes.get(1));
        assertEquals(WorkflowState.PENDING_A, journal.get("unlucky").state());
    }

    @Test void administrativeInspectionAndStandaloneExamplesUsePersistedData() {
        assertTrue(admin.demonstrateStandalone("gate", "solo-a").contains("CANCELLED"));
        assertTrue(admin.demonstrateStandalone("spaces", "solo-b").contains("RELEASED"));
        assertTrue(admin.demonstrateStandalone("payment", "solo-c").contains("REFUNDED"));
        theGarageRestarts();
        String report = admin.inspect();
        for (String text : List.of("solo-a", "solo-b", "solo-c", "CANCELLED", "RELEASED", "REFUNDED")) assertTrue(report.contains(text));
        assertTrue(journal.all().isEmpty());
        assertThrows(DomainException.class, () -> admin.demonstrateStandalone("unknown", "invalid"));
        assertThrows(DomainException.class, () -> admin.history("unknown"));
        assertThrows(DomainException.class, () -> admin.recover("unknown"));
    }

    @Test void invalidIdsAreRejectedAndPlateNormalizationKeepsRepeatedRequestsConsistent() {
        assertThrows(DomainException.class, () -> new ParkingRequest(null, "CAR123", 180, 1, 1, 500));
        assertThrows(DomainException.class, () -> new ParkingRequest("../bad", "CAR123", 180, 1, 1, 500));
        assertEquals("CAR123", new ParkingRequest("id", " car123 ", 180, 1, 1, 500).plate());
        assertEquals(WorkflowState.REJECTED, parking.park(new ParkingRequest("blank", null, 180, 1, 1, 500)).state());
    }

    @Test void newWorkflowCannotClaimOrCompensateUnrelatedStandaloneEntities() {
        gate.hold("standalone-a", "OTHER123", 180);
        spaces.reserve("standalone-b", 2);
        payments.charge("standalone-c", 1, 500);
        for (String id : List.of("standalone-a", "standalone-b", "standalone-c")) {
            assertThrows(DomainException.class, () -> parking.park(aDriverRequestsParking(id)));
            assertTrue(journal.find(id).isEmpty());
        }
        assertEquals(GateAdmission.Status.HELD, gate.find("standalone-a").orElseThrow().status());
        assertEquals(ParkingReservation.Status.RESERVED, spaces.find("standalone-b").orElseThrow().status());
        assertEquals(ParkingPayment.Status.CAPTURED, payments.find("standalone-c").orElseThrow().status());
    }

    @Test void standaloneDemonstrationCannotModifyWorkflowOwnedEntities() {
        parking.park(aDriverRequestsParking("workflow"));
        assertThrows(DomainException.class, () -> admin.demonstrateStandalone("gate", "workflow"));
        assertBookingIsDurablyConfirmed("workflow");
    }
}
