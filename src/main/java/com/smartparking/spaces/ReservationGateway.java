package com.smartparking.spaces;

import com.smartparking.infrastructure.Database;
import com.smartparking.infrastructure.DomainException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Sensor readings and allocation records share one atomic subsystem transaction. */
public final class ReservationGateway {
    private final Database database;

    public ReservationGateway(Database database) {
        this.database = database;
        database.transaction(connection -> {
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS reservations (
                      operation_id TEXT PRIMARY KEY, space INTEGER NOT NULL CHECK(space BETWEEN 1 AND 3),
                      status TEXT NOT NULL CHECK(status IN ('RESERVED','OCCUPIED','RELEASED')))
                    """);
                statement.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS one_active_reservation_per_space ON reservations(space) WHERE status <> 'RELEASED'");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS space_sensors (space INTEGER PRIMARY KEY CHECK(space BETWEEN 1 AND 3), blocked INTEGER NOT NULL CHECK(blocked IN (0,1)))");
            }
            return null;
        });
    }

    public ParkingReservation reserve(ParkingReservation requested, SpaceSensorGrid sensorGrid) {
        return database.transaction(connection -> {
            var existing = find(connection, requested.operationId());
            if (existing.isPresent()) {
                var saved = existing.get();
                if (saved.space() != requested.space()) {
                    throw new DomainException("Reservation operation ID already belongs to a different space.");
                }
                if (saved.status() == ParkingReservation.Status.RELEASED) {
                    throw new DomainException("A released reservation cannot be reused.");
                }
                return saved;
            }
            sensorGrid.requireAvailable(requested.space(), isBlocked(connection, requested.space()),
                    isAllocated(connection, requested.space()));
            try (var statement = connection.prepareStatement("INSERT INTO reservations VALUES (?, ?, ?)")) {
                statement.setString(1, requested.operationId());
                statement.setInt(2, requested.space());
                statement.setString(3, requested.status().name());
                statement.executeUpdate();
            }
            return requested;
        });
    }

    public void occupy(String operationId) {
        database.transaction(connection -> {
            var reservation = find(connection, operationId)
                    .orElseThrow(() -> new DomainException("No reservation exists for " + operationId));
            if (reservation.status() != ParkingReservation.Status.OCCUPIED) {
                update(connection, reservation.occupy());
            }
            return null;
        });
    }

    public void release(String operationId) {
        database.transaction(connection -> {
            var reservation = find(connection, operationId);
            if (reservation.isPresent() && reservation.get().status() != ParkingReservation.Status.RELEASED) {
                update(connection, reservation.get().release());
            }
            return null;
        });
    }

    public void setSpaceBlocked(int space, boolean blocked) {
        database.transaction(connection -> {
            try (var statement = connection.prepareStatement("INSERT INTO space_sensors(space, blocked) VALUES (?, ?) ON CONFLICT(space) DO UPDATE SET blocked = excluded.blocked")) {
                statement.setInt(1, space);
                statement.setInt(2, blocked ? 1 : 0);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public boolean isSpaceBlocked(int space) {
        return database.transaction(connection -> isBlocked(connection, space));
    }

    public Optional<ParkingReservation> find(String operationId) {
        return database.transaction(connection -> find(connection, operationId));
    }

    public List<ParkingReservation> all() {
        return database.transaction(connection -> {
            List<ParkingReservation> result = new ArrayList<>();
            try (var statement = connection.prepareStatement("SELECT * FROM reservations ORDER BY operation_id");
                 var rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(read(rows));
                }
            }
            return List.copyOf(result);
        });
    }

    private Optional<ParkingReservation> find(Connection connection, String id) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM reservations WHERE operation_id = ?")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        }
    }

    private boolean isBlocked(Connection connection, int space) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT blocked FROM space_sensors WHERE space = ?")) {
            statement.setInt(1, space);
            try (var rows = statement.executeQuery()) {
                return rows.next() && rows.getInt(1) != 0;
            }
        }
    }

    private boolean isAllocated(Connection connection, int space) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT 1 FROM reservations WHERE space = ? AND status <> 'RELEASED'")) {
            statement.setInt(1, space);
            try (var rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private ParkingReservation read(ResultSet rows) throws SQLException {
        return new ParkingReservation(rows.getString("operation_id"), rows.getInt("space"),
                ParkingReservation.Status.valueOf(rows.getString("status")));
    }

    private void update(Connection connection, ParkingReservation reservation) throws SQLException {
        try (var statement = connection.prepareStatement("UPDATE reservations SET status = ? WHERE operation_id = ?")) {
            statement.setString(1, reservation.status().name());
            statement.setString(2, reservation.operationId());
            statement.executeUpdate();
        }
    }
}
