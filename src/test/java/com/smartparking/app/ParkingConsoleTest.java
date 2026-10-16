package com.smartparking.app;

import com.smartparking.infrastructure.DomainException;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Scanner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real examiner-facing flows against a fresh SQLite file; no mocks or external processes. */
class ParkingConsoleTest {
    @TempDir Path directory;
    private Path store;
    private GarageRuntime garage;

    @BeforeEach void openEmptyGarage() {
        store = directory.resolve("garage.db");
        garage = new GarageRuntime(store);
    }

    @AfterEach void closeGarage() { garage.close(); }

    @Test void invalidMenuChoiceExplainsTheProblemAndAllowsCleanExit() {
        String transcript = examinerEnters("not-a-choice\n0\n");
        assertAll(
                () -> assertTrue(transcript.contains("Invalid choice")),
                () -> assertTrue(transcript.contains("Goodbye. Persisted state retained.")),
                () -> assertEquals(0, persistedRows("workflows")));
    }

    @Test void invalidNumericInputDoesNotCreateAnUnfinishedWorkflow() {
        String transcript = examinerEnters("1\nbad-number\nCAR123\ntall\n0\n");
        assertAll(
                () -> assertTrue(transcript.contains("Enter a whole number")),
                () -> assertTrue(transcript.contains("Goodbye.")),
                () -> assertEquals(0, persistedRows("workflows")),
                () -> assertEquals(0, persistedRows("gate_admissions")));
    }

    @Test void endOfInputDuringPromptsExitsWithoutWritingAPartialRequest() {
        String transcript = examinerEnters("1\npartial-request\n");
        assertTrue(transcript.contains("Input ended. Persisted state retained."));
        assertEquals(0, persistedRows("workflows"));
        assertDoesNotThrow(() -> examinerEnters(""));
    }

    @Test void customVehicleInputCommitsAllStagesAndCanBeInspected() {
        String transcript = examinerEnters("1\ncustom-visit\nKZ123AB\n190\n1\n3\n1700\n4\n5\ncustom-visit\n0\n");
        assertAppearsInOrder(transcript, "A committed:", "B committed:", "C committed:");
        assertAll(
                () -> assertTrue(transcript.contains("Final persisted state: COMPLETED")),
                () -> assertTrue(transcript.contains("KZ123AB")),
                () -> assertTrue(transcript.contains("amountCents=1500")),
                () -> assertEquals("HELD", persistedStatus("gate_admissions", "custom-visit")),
                () -> assertEquals("RESERVED", persistedStatus("reservations", "custom-visit")),
                () -> assertEquals("CAPTURED", persistedStatus("payments", "custom-visit")),
                () -> assertEquals("COMPLETED", workflowState("custom-visit")));
    }

    @Test void demonstrationAtBFailsAfterAAndShowsOnlyACompensation() {
        String transcript = examinerRuns("demo-b", "blocked-visit");
        assertAppearsInOrder(transcript, "A committed:", "Stage B executing:", "Starting reverse compensation.",
                "Compensation A committed:", "Final persisted state: COMPENSATED");
        assertAll(
                () -> assertFalse(transcript.contains("B committed:")),
                () -> assertFalse(transcript.contains("Compensation B committed:")),
                () -> assertEquals("CANCELLED", persistedStatus("gate_admissions", "blocked-visit")),
                () -> assertEquals(0, persistedRows("reservations")),
                () -> assertEquals(0, persistedRows("payments")),
                () -> assertEquals("COMPENSATED", workflowState("blocked-visit")));
    }

    @Test void demonstrationAtCShowsCommittedStagesAndReverseCompensationOrder() {
        String transcript = examinerRuns("demo-c", "underpaid-visit");
        assertAppearsInOrder(transcript, "A committed:", "B committed:", "Stage C executing:",
                "Compensation B committed:", "Compensation A committed:", "Final persisted state: COMPENSATED");
        assertAll(
                () -> assertFalse(transcript.contains("C committed:")),
                () -> assertTrue(transcript.contains("1000 cents required, but only 100 tendered")),
                () -> assertEquals("RELEASED", persistedStatus("reservations", "underpaid-visit")),
                () -> assertEquals("CANCELLED", persistedStatus("gate_admissions", "underpaid-visit")),
                () -> assertEquals(0, persistedRows("payments")),
                () -> assertEquals("COMPENSATED", workflowState("underpaid-visit")));
    }

    @Test void interactiveFailureMenusReachBothRequiredCompensationDepths() {
        String transcript = examinerEnters("2\n3\n0\n");
        assertEquals(2, persistedRows("workflows"));
        assertEquals(2, occurrences(transcript, "Final persisted state: COMPENSATED"));
        assertEquals(2, occurrences(transcript, "Compensation A committed:"));
        assertEquals(1, occurrences(transcript, "Compensation B committed:"));
    }

    @Test void persistedSensorCanBeClearedToAllowANewBooking() {
        assertTrue(examinerRuns("sensor", "2", "blocked").contains("space 2 = blocked"));
        assertTrue(examinerRuns("park", "blocked", "BLOCK123", "180", "2", "2", "1000")
                .contains("Final persisted state: COMPENSATED"));
        assertTrue(examinerEnters("10\n2\nclear\n0\n").contains("space 2 = clear"));
        assertTrue(examinerRuns("park", "cleared", "CLEAR123", "180", "2", "2", "1000")
                .contains("Final persisted state: COMPLETED"));
        assertEquals("RESERVED", persistedStatus("reservations", "cleared"));
    }

    @Test void wrongCommandsAndInvalidSensorStateFailClearlyWithoutDomainWrites() {
        assertAll(
                () -> assertThrows(DomainException.class, () -> examinerRuns("unknown")),
                () -> assertThrows(DomainException.class, () -> examinerRuns("park", "missing-fields")),
                () -> assertThrows(DomainException.class, () -> examinerRuns("sensor", "2", "maybe")),
                () -> assertThrows(DomainException.class, () -> examinerRuns("sensor", "2147483648", "clear")),
                () -> assertEquals(0, persistedRows("workflows")),
                () -> assertEquals(0, persistedRows("space_sensors")));
    }

    @Test void checkpointSurvivesReopeningAndRecoveryMenuFinishesIt() {
        String checkpoint = examinerEnters("9\nresume-me\nRESTART123\n180\n1\n2\n1000\n0\n");
        assertTrue(checkpoint.contains("Final persisted state: PENDING_B"));
        assertEquals("PENDING_B", workflowState("resume-me"));
        garage.close();
        garage = new GarageRuntime(store);

        String transcript = examinerEnters("6\n5\nresume-me\n0\n");
        assertAll(
                () -> assertTrue(transcript.contains("Incomplete persisted operations: [resume-me]")),
                () -> assertTrue(transcript.contains("resume-me -> COMPLETED")),
                () -> assertTrue(transcript.contains("Final persisted state: COMPLETED")),
                () -> assertEquals("COMPLETED", workflowState("resume-me")),
                () -> assertEquals(1, persistedRows("payments")));
    }

    @Test void cancellationMenuRefundsACompletedBookingAndIsSafeToRepeat() {
        examinerRuns("park", "cancel-me", "CANCEL123", "180", "1", "2", "1000");
        String transcript = examinerEnters("7\ncancel-me\n0\n");
        assertAppearsInOrder(transcript, "Compensation C committed:", "Compensation B committed:",
                "Compensation A committed:");
        assertEquals("REFUNDED", persistedStatus("payments", "cancel-me"));
        assertEquals("COMPENSATED", workflowState("cancel-me"));
        assertEquals(1, occurrences(examinerRuns("cancel", "cancel-me"), "Compensation C committed:"));
    }

    @Test void standaloneMenuDoesNotCreateAThreeStageWorkflow() {
        String transcript = examinerEnters("8\ngate\n0\n");
        assertTrue(transcript.contains("Independent A: HELD -> ADMITTED -> CANCELLED"));
        assertEquals(1, persistedRows("gate_admissions"));
        assertEquals(0, persistedRows("workflows"));
        assertEquals(0, persistedRows("reservations"));
        assertEquals(0, persistedRows("payments"));
    }

    private String examinerEnters(String input) {
        var bytes = new ByteArrayOutputStream();
        try (var scanner = new Scanner(input); var output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            new ParkingConsole(garage.parking(), garage.administration(), scanner, output).interactive();
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private String examinerRuns(String... arguments) {
        var bytes = new ByteArrayOutputStream();
        try (var scanner = new Scanner(""); var output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            new ParkingConsole(garage.parking(), garage.administration(), scanner, output).command(List.of(arguments));
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private String persistedStatus(String table, String operation) { return persistedColumn(table, "status", operation); }
    private String workflowState(String operation) { return persistedColumn("workflows", "state", operation); }

    /** Independent JDBC reads verify the CLI wrote durable state, not just reassuring output. */
    private String persistedColumn(String table, String column, String operation) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + store);
             var query = connection.prepareStatement("SELECT " + column + " FROM " + table + " WHERE operation_id=?")) {
            query.setString(1, operation);
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next(), "Expected a persisted " + table + " record for " + operation);
                return rows.getString(1);
            }
        } catch (java.sql.SQLException failure) { throw new AssertionError(failure); }
    }

    private int persistedRows(String table) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + store);
             var query = connection.createStatement();
             var rows = query.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        } catch (java.sql.SQLException failure) { throw new AssertionError(failure); }
    }

    private static void assertAppearsInOrder(String transcript, String... steps) {
        int previous = -1;
        for (String step : steps) {
            int current = transcript.indexOf(step);
            assertTrue(current > previous, "Expected " + step + " after the preceding step:\n" + transcript);
            previous = current;
        }
    }

    private static int occurrences(String text, String fragment) {
        return (text.length() - text.replace(fragment, "").length()) / fragment.length();
    }
}
