package com.smartparking.app;

import com.smartparking.facade.AdministrationFacade;
import com.smartparking.facade.ParkingFacade;
import com.smartparking.infrastructure.DomainException;
import com.smartparking.workflow.ParkingRequest;
import com.smartparking.workflow.Workflow;
import java.io.PrintStream;
import java.util.List;
import java.util.Scanner;
import java.util.UUID;

/** Client: knows two facades and their request/result data, not gateways or subsystem entities. */
public final class ParkingConsole {
    private final ParkingFacade parking;
    private final AdministrationFacade administration;
    private final Scanner input;
    private final PrintStream output;

    public ParkingConsole(ParkingFacade parking, AdministrationFacade administration,
                          Scanner input, PrintStream output) {
        this.parking = parking; this.administration = administration;
        this.input = input; this.output = output;
    }

    public void interactive() {
        output.println("SMART PARKING GARAGE | prepaid booking | 3 spaces | 500 cents/hour");
        List<String> pending = administration.incompleteOperations();
        output.println("Incomplete persisted operations: " + pending + ". Use recovery to continue them.");
        while (true) {
            output.println("""

                1  Park a vehicle (real input)
                2  Demonstrate B failure (block sensor for space 2)
                3  Demonstrate C failure (insufficient tender; needs free space 3)
                4  Inspect all persisted state
                5  Inspect operation history
                6  Recover all incomplete operations
                7  Cancel/compensate an operation
                8  Demonstrate one independent subsystem
                9  Save checkpoint after A (restart demonstration)
                10 Configure a space sensor
                0  Exit (keep database)
                """);
            if (!input.hasNextLine()) return;
            output.print("Choice: ");
            String choice = input.nextLine().trim();
            try {
                switch (choice) {
                    case "0" -> { output.println("Goodbye. Persisted state retained."); return; }
                    case "1" -> show(parking.park(readRequest()));
                    case "2" -> demoB(newId());
                    case "3" -> demoC(newId());
                    case "4" -> output.println(administration.inspect());
                    case "5" -> output.println(administration.history(ask("Operation ID", null)));
                    case "6" -> output.println(administration.recoverAll());
                    case "7" -> show(administration.cancel(ask("Operation ID", null)));
                    case "8" -> output.println(administration.demonstrateStandalone(
                            ask("Subsystem: gate, spaces, payment", "gate"), newId()));
                    case "9" -> show(administration.checkpoint(readRequest()));
                    case "10" -> setSensor(number(ask("Space (1-3)", "2")), ask("blocked or clear", "clear"));
                    default -> output.println("Invalid choice. Enter one of the displayed numbers.");
                }
            } catch (EndOfInput ignored) { output.println("Input ended. Persisted state retained."); return; }
            catch (RuntimeException failure) { output.println("Operation could not finish: " + failure.getMessage()); }
        }
    }

    /** Commands use the same facade calls as the interactive menu, useful for reproducible demonstrations. */
    public void command(List<String> args) {
        String command = args.get(0);
        switch (command) {
            case "inspect" -> { requireSize(args, 1); output.println(administration.inspect()); }
            case "history" -> { requireSize(args, 2); output.println(administration.history(args.get(1))); }
            case "park", "checkpoint" -> {
                requireSize(args, 7);
                ParkingRequest request = new ParkingRequest(args.get(1), args.get(2), number(args.get(3)),
                        number(args.get(4)), number(args.get(5)), number(args.get(6)));
                show(command.equals("park") ? parking.park(request) : administration.checkpoint(request));
            }
            case "demo-b" -> { requireSize(args, 2); demoB(args.get(1)); }
            case "demo-c" -> { requireSize(args, 2); demoC(args.get(1)); }
            case "recover" -> {
                if (args.size() == 1) output.println(administration.recoverAll());
                else { requireSize(args, 2); show(administration.recover(args.get(1))); }
            }
            case "cancel" -> { requireSize(args, 2); show(administration.cancel(args.get(1))); }
            case "sensor" -> { requireSize(args, 3); setSensor(number(args.get(1)), args.get(2)); }
            case "standalone" -> { requireSize(args, 3); output.println(administration.demonstrateStandalone(args.get(1), args.get(2))); }
            default -> throw new DomainException("Unknown command. Use inspect, history, park, checkpoint, demo-b, demo-c, recover, cancel, sensor or standalone.");
        }
    }

    private void demoB(String id) {
        administration.setSpaceBlocked(2, true);
        output.println("Real persisted sensor reading: space 2 BLOCKED. A will commit before B checks it.");
        show(parking.park(new ParkingRequest(id, "BFAIL123", 180, 2, 2, 1000)));
    }

    private void demoC(String id) {
        output.println("Payment tender: 100 cents for 2 hours costing 1000 cents. Space 3 must be available.");
        show(parking.park(new ParkingRequest(id, "CFAIL123", 180, 3, 2, 100)));
    }

    private void setSensor(int space, String state) {
        if (!state.equals("blocked") && !state.equals("clear")) throw new DomainException("Sensor state must be blocked or clear.");
        administration.setSpaceBlocked(space, state.equals("blocked"));
        output.println("Persisted sensor: space " + space + " = " + state);
    }

    private void show(Workflow workflow) { output.println(administration.history(workflow.request().operationId())); }

    private ParkingRequest readRequest() {
        return new ParkingRequest(ask("Operation ID (reuse only for the same request)", newId()),
                ask("License plate", "CAR123"), number(ask("Height cm (maximum 220)", "180")),
                number(ask("Space (1-3)", "1")), number(ask("Prepaid hours (1-24)", "2")),
                number(ask("Tender in cents", "1000")));
    }

    private String ask(String label, String fallback) {
        output.print(label + (fallback == null ? "" : " [" + fallback + "]") + ": ");
        if (!input.hasNextLine()) throw new EndOfInput();
        String value = input.nextLine().trim();
        if (value.isEmpty() && fallback != null) return fallback;
        return value;
    }

    private static int number(String text) {
        try { return Integer.parseInt(text); }
        catch (NumberFormatException invalid) { throw new DomainException("Enter a whole number within the 32-bit integer range."); }
    }

    private static String newId() { return "visit-" + UUID.randomUUID().toString().substring(0, 8); }
    private static void requireSize(List<String> args, int size) {
        if (args.size() != size) throw new DomainException("Wrong argument count for " + args.get(0) + ". See README command examples.");
    }
    private static final class EndOfInput extends RuntimeException { }
}
