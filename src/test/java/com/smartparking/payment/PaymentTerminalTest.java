package com.smartparking.payment;

import com.smartparking.infrastructure.Database;
import com.smartparking.infrastructure.DomainException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class PaymentTerminalTest {
    @TempDir Path directory;
    private Database database;
    private PaymentTerminal terminal;

    @BeforeEach void openAnEmptyTerminal() { reopenTerminal(); }
    @AfterEach void closeTerminal() { database.close(); }

    private void reopenTerminal() {
        database = new Database(directory.resolve("payment.db"));
        terminal = new PaymentTerminal(new PaymentGateway(database), new OverstayAlert());
    }

    private ParkingPayment aDriverPrepaysForTwoHours() { return terminal.charge("visit-1", 2, 1500); }

    @Test void aStandaloneTerminalCapturesTheTariffRatherThanTheEntireTender() {
        var payment = aDriverPrepaysForTwoHours();
        assertEquals(ParkingPayment.Status.CAPTURED, payment.status());
        assertEquals(1000, payment.amountCents());
        assertEquals(1500, payment.tenderCents());
        assertEquals(payment, terminal.find("visit-1").orElseThrow());
        assertEquals(1, terminal.all().size());
    }

    @Test void aStandaloneAuthorizationCanBeCapturedAndRefunded() {
        assertEquals(ParkingPayment.Status.AUTHORIZED, terminal.authorize("visit-1", 2, 1500).status());
        terminal.capture("visit-1");
        terminal.capture("visit-1");
        assertEquals(ParkingPayment.Status.CAPTURED, terminal.find("visit-1").orElseThrow().status());
        terminal.refund("visit-1");
        assertEquals(ParkingPayment.Status.REFUNDED, terminal.find("visit-1").orElseThrow().status());
    }

    @Test void chargingAnExistingAuthorizationCapturesItOnlyOnce() {
        terminal.authorize("visit-1", 2, 1500);
        var paid = aDriverPrepaysForTwoHours();
        assertEquals(paid, aDriverPrepaysForTwoHours());
        assertEquals(paid, terminal.authorize("visit-1", 2, 1500));
        assertEquals(1, terminal.all().size());
    }

    @Test void insufficientTenderFailsBeforeAnyPaymentIsPersisted() {
        var error = assertThrows(DomainException.class, () -> terminal.charge("short", 2, 999));
        assertTrue(error.getMessage().contains("1000 cents required"));
        assertTrue(terminal.find("short").isEmpty());
        assertTrue(terminal.all().isEmpty());
        assertEquals(1000, terminal.charge("exact", 2, 1000).amountCents());
    }

    @Test void anOperationIdCannotBeReassignedToDifferentHoursOrTender() {
        var original = aDriverPrepaysForTwoHours();
        assertThrows(DomainException.class, () -> terminal.charge("visit-1", 1, 1500));
        assertThrows(DomainException.class, () -> terminal.charge("visit-1", 2, 1000));
        assertEquals(original, terminal.find("visit-1").orElseThrow());
    }

    @Test void repeatedRefundIsSafeAndARefundedPaymentCannotBeCapturedAgain() {
        aDriverPrepaysForTwoHours();
        terminal.refund("visit-1");
        terminal.refund("visit-1");
        terminal.refund("unknown");
        assertThrows(DomainException.class, () -> terminal.charge("visit-1", 2, 1500));
        assertThrows(DomainException.class, () -> terminal.capture("visit-1"));
        assertEquals(ParkingPayment.Status.REFUNDED, terminal.find("visit-1").orElseThrow().status());
    }

    @Test void invalidPrepaidDurationAndIdsAreRejected() {
        assertThrows(DomainException.class, () -> terminal.charge("zero", 0, 1000));
        assertThrows(DomainException.class, () -> terminal.charge("long", 25, 12500));
        assertThrows(DomainException.class, () -> terminal.charge(null, 1, 500));
        assertThrows(DomainException.class, () -> terminal.charge(" ", 1, 500));
        assertThrows(DomainException.class, () -> terminal.capture("unknown"));
        assertTrue(terminal.all().isEmpty());
        assertEquals(12000, terminal.charge("limit", 24, 12000).amountCents());
    }

    @Test void entityTransitionsAreEnforcedWithoutTheTerminal() {
        var authorized = new ParkingPayment("id", 2, 1000, 1000, ParkingPayment.Status.AUTHORIZED);
        var captured = authorized.capture();
        var refunded = captured.refund();
        assertEquals(ParkingPayment.Status.REFUNDED, authorized.refund().status());
        assertThrows(DomainException.class, captured::capture);
        assertThrows(DomainException.class, refunded::capture);
        assertThrows(DomainException.class, refunded::refund);
        assertEquals(ParkingPayment.Status.AUTHORIZED, authorized.status());
    }

    @Test void entityRejectsCorruptAmountsAndStatuses() {
        assertThrows(DomainException.class, () -> new ParkingPayment("id", 0, 1000, 0, ParkingPayment.Status.AUTHORIZED));
        assertThrows(DomainException.class, () -> new ParkingPayment("id", 25, 12500, 12500, ParkingPayment.Status.AUTHORIZED));
        assertThrows(DomainException.class, () -> new ParkingPayment("id", 2, 1000, 900, ParkingPayment.Status.AUTHORIZED));
        assertThrows(DomainException.class, () -> new ParkingPayment("id", 2, 999, 1000, ParkingPayment.Status.AUTHORIZED));
        assertThrows(DomainException.class, () -> new ParkingPayment("id", 2, 1000, 1000, null));
    }

    @Test void theOverstayPolicyAlertsOnlyAfterThePurchasedDuration() {
        var alert = new OverstayAlert();
        assertEquals(0, alert.overdueMinutes(2, 119));
        assertEquals(0, alert.overdueMinutes(2, 120));
        assertEquals(1, alert.overdueMinutes(2, 121));
        assertThrows(DomainException.class, () -> alert.overdueMinutes(2, -1));
        assertThrows(DomainException.class, () -> alert.overdueMinutes(0, 10));
    }

    @Test void aPaymentAndItsRefundSurviveClosingAndReopeningTheStore() {
        var paid = aDriverPrepaysForTwoHours();
        database.close();
        reopenTerminal();
        assertEquals(paid, terminal.find("visit-1").orElseThrow());
        terminal.refund("visit-1");
        database.close();
        reopenTerminal();
        assertEquals(ParkingPayment.Status.REFUNDED, terminal.find("visit-1").orElseThrow().status());
    }
}
