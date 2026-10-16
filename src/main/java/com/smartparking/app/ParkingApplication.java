package com.smartparking.app;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Scanner;

public final class ParkingApplication {
    private ParkingApplication() { }

    public static void main(String[] args) {
        int exitCode = run(args);
        if (exitCode != 0) System.exit(exitCode);
    }

    static int run(String[] args) {
        try {
            var arguments = new ArrayList<>(Arrays.asList(args));
            Path path = Path.of("data", "parking.db");
            if (!arguments.isEmpty() && arguments.get(0).equals("--db")) {
                if (arguments.size() < 2) { System.err.println("--db requires a database file path."); return 2; }
                path = Path.of(arguments.remove(1));
                arguments.remove(0);
            }
            path = path.toAbsolutePath().normalize();
            // One application process owns a store. The OS releases the lock even after abrupt termination.
            Files.createDirectories(path.getParent());
            Path lockPath = path.resolveSibling(path.getFileName() + ".lock");
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock lock = channel.tryLock()) {
                if (lock == null) { System.err.println("This database is already open in another garage process."); return 2; }
                try (var runtime = new GarageRuntime(path)) {
                    var console = new ParkingConsole(runtime.parking(), runtime.administration(),
                            new Scanner(System.in), System.out);
                    System.out.println("SQLite file: " + path);
                    if (arguments.isEmpty()) console.interactive();
                    else console.command(arguments);
                }
            }
            return 0;
        } catch (Exception failure) {
            System.err.println("Cannot complete operation: " + failure.getMessage());
            return 2;
        }
    }
}
