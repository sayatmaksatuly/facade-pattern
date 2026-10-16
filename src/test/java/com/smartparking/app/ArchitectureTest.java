package com.smartparking.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Source-level guard for the assignment's explicit package-boundary requirements. */
class ArchitectureTest {
    private static final Path SOURCE = Path.of("src", "main", "java", "com", "smartparking");

    @ParameterizedTest
    @ValueSource(strings = {"gate", "spaces", "payment"})
    void eachSubsystemHasThreeToFiveClassesAndNoCrossSubsystemReferences(String subsystem) throws IOException {
        try (var files = Files.list(SOURCE.resolve(subsystem))) {
            List<Path> classes = files.filter(path -> path.toString().endsWith(".java")).toList();
            assertTrue(classes.size() >= 3 && classes.size() <= 5, "Each subsystem needs 3-5 meaningful top-level classes");
            for (Path file : classes) {
                String source = Files.readString(file);
                for (String other : List.of("gate", "spaces", "payment")) {
                    if (!other.equals(subsystem)) {
                        assertFalse(source.contains("com.smartparking." + other + "."),
                                file.getFileName() + " must not know the " + other + " subsystem");
                    }
                }
                assertFalse(source.contains("com.smartparking.facade."), "Subsystems cannot depend on their facades");
            }
        }
    }

    @Test void interactiveClientKnowsFacadesAndNoSubsystemOrSqlClasses() throws IOException {
        String client = Files.readString(SOURCE.resolve("app/ParkingConsole.java"));
        for (String forbidden : List.of("com.smartparking.gate.", "com.smartparking.spaces.",
                "com.smartparking.payment.", "java.sql.")) {
            assertFalse(client.contains(forbidden), "Client should communicate through the facades: " + forbidden);
        }
        assertTrue(client.contains("import com.smartparking.facade.ParkingFacade;"));
        assertTrue(client.contains("import com.smartparking.facade.AdministrationFacade;"));
    }
}
