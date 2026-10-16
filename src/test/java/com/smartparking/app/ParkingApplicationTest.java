package com.smartparking.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ParkingApplicationTest {
    @TempDir Path directory;

    @Test void invalidDatabaseArgumentIsReportedWithoutAnUnhandledException() {
        assertEquals(2, ParkingApplication.run(new String[]{"--db"}));
        assertEquals(2, ParkingApplication.run(new String[]{"--db", "invalid\u0000path"}));
    }

    @Test void entryPointOpensDatabaseForCommandsAndReturnsFailureForMalformedCommand() {
        String db = directory.resolve("parking.db").toString();
        assertEquals(0, ParkingApplication.run(new String[]{"--db", db, "inspect"}));
        assertEquals(2, ParkingApplication.run(new String[]{"--db", db, "unknown"}));
    }
}
