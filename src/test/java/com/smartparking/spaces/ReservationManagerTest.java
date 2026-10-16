package com.smartparking.spaces;

import com.smartparking.infrastructure.Database;
import com.smartparking.infrastructure.DomainException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ReservationManagerTest {
    @TempDir Path directory;
    private Database database;
    private ReservationManager reservations;

    @BeforeEach void openAnEmptyGarage() { reopenReservations(); }
    @AfterEach void closeGarage() { database.close(); }

    private void reopenReservations() {
        database = new Database(directory.resolve("spaces.db"));
        reservations = new ReservationManager(new ReservationGateway(database), new SpaceSensorGrid());
    }

    private ParkingReservation aDriverHasReservedSpaceOne() { return reservations.reserve("visit-1", 1); }

    @Test void aStandaloneReservationCanBeOccupiedAndReleased() {
        assertEquals(ParkingReservation.Status.RESERVED, aDriverHasReservedSpaceOne().status());
        reservations.occupy("visit-1");
        reservations.occupy("visit-1");
        assertEquals(ParkingReservation.Status.OCCUPIED, reservations.find("visit-1").orElseThrow().status());
        reservations.release("visit-1");
        assertEquals(ParkingReservation.Status.RELEASED, reservations.all().get(0).status());
        assertEquals(ParkingReservation.Status.RESERVED, reservations.reserve("next-driver", 1).status());
    }

    @Test void anAllocatedSpaceRejectsAnotherDriverWithoutLosingTheOriginalReservation() {
        var original = aDriverHasReservedSpaceOne();
        assertThrows(DomainException.class, () -> reservations.reserve("second-driver", 1));
        reservations.occupy("visit-1");
        assertThrows(DomainException.class, () -> reservations.reserve("second-driver", 1));
        assertTrue(reservations.find("second-driver").isEmpty());
        assertEquals(original.space(), reservations.all().get(0).space());
        assertEquals(1, reservations.all().size());
    }

    @Test void aBlockedSensorRejectsAllocationAndCanBeCleared() {
        assertFalse(reservations.isSpaceBlocked(2));
        reservations.setSpaceBlocked(2, true);
        assertTrue(reservations.isSpaceBlocked(2));
        assertThrows(DomainException.class, () -> reservations.reserve("visit-2", 2));
        assertTrue(reservations.all().isEmpty());
        reservations.setSpaceBlocked(2, false);
        assertFalse(reservations.isSpaceBlocked(2));
        assertEquals(2, reservations.reserve("visit-2", 2).space());
    }

    @Test void duplicateRequestsAreIdempotentEvenIfTheSensorLaterChanges() {
        var original = aDriverHasReservedSpaceOne();
        reservations.setSpaceBlocked(1, true);
        assertEquals(original, reservations.reserve("visit-1", 1));
        reservations.occupy("visit-1");
        assertEquals(ParkingReservation.Status.OCCUPIED, reservations.reserve("visit-1", 1).status());
        assertThrows(DomainException.class, () -> reservations.reserve("visit-1", 2));
        assertEquals(1, reservations.all().size());
    }

    @Test void repeatedCompensationCannotResurrectAnAllocation() {
        aDriverHasReservedSpaceOne();
        reservations.release("visit-1");
        reservations.release("visit-1");
        reservations.release("unknown");
        assertThrows(DomainException.class, () -> reservations.reserve("visit-1", 1));
        assertThrows(DomainException.class, () -> reservations.occupy("visit-1"));
        assertEquals(ParkingReservation.Status.RELEASED, reservations.find("visit-1").orElseThrow().status());
    }

    @Test void invalidSpaceAndOperationIdsNeverCreateRecords() {
        assertThrows(DomainException.class, () -> reservations.reserve("invalid", 0));
        assertThrows(DomainException.class, () -> reservations.reserve("invalid", 4));
        assertThrows(DomainException.class, () -> reservations.reserve(null, 1));
        assertThrows(DomainException.class, () -> reservations.reserve(" ", 1));
        assertThrows(DomainException.class, () -> reservations.setSpaceBlocked(0, true));
        assertThrows(DomainException.class, () -> reservations.isSpaceBlocked(4));
        assertThrows(DomainException.class, () -> reservations.occupy("unknown"));
        assertTrue(reservations.all().isEmpty());
    }

    @Test void entityTransitionsAreEnforcedWithoutTheManager() {
        var reserved = new ParkingReservation("visit", 1, ParkingReservation.Status.RESERVED);
        var occupied = reserved.occupy();
        var released = occupied.release();
        assertEquals(ParkingReservation.Status.RELEASED, reserved.release().status());
        assertThrows(DomainException.class, occupied::occupy);
        assertThrows(DomainException.class, released::occupy);
        assertThrows(DomainException.class, released::release);
        assertEquals(ParkingReservation.Status.RESERVED, reserved.status());
    }

    @Test void entityRejectsInvalidRestoredSpaceAndStatus() {
        assertThrows(DomainException.class, () -> new ParkingReservation("id", 0, ParkingReservation.Status.RESERVED));
        assertThrows(DomainException.class, () -> new ParkingReservation("id", 4, ParkingReservation.Status.RESERVED));
        assertThrows(DomainException.class, () -> new ParkingReservation("id", 1, null));
    }

    @Test void aReservationAndAnObstructionSurviveAStoreRestart() {
        var reservation = aDriverHasReservedSpaceOne();
        reservations.setSpaceBlocked(3, true);
        database.close();
        reopenReservations();
        assertEquals(reservation, reservations.find("visit-1").orElseThrow());
        assertTrue(reservations.isSpaceBlocked(3));
        assertThrows(DomainException.class, () -> reservations.reserve("blocked", 3));
        reservations.release("visit-1");
        database.close();
        reopenReservations();
        assertEquals(ParkingReservation.Status.RELEASED, reservations.find("visit-1").orElseThrow().status());
    }
}
