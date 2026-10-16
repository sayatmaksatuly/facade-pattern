package com.smartparking.gate;

import com.smartparking.infrastructure.Database;
import com.smartparking.infrastructure.DomainException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Owns gate SQL and makes read-modify-write operations atomic. */
public final class GateGateway {
    private final Database database;

    public GateGateway(Database database) {
        this.database = database;
        database.transaction(connection -> {
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS gate_admissions (
                      operation_id TEXT PRIMARY KEY, plate TEXT NOT NULL,
                      height_cm INTEGER NOT NULL CHECK(height_cm BETWEEN 1 AND 220),
                      status TEXT NOT NULL CHECK(status IN ('HELD','ADMITTED','CANCELLED')))
                    """);
            }
            return null;
        });
    }

    public GateAdmission hold(GateAdmission requested) {
        return database.transaction(connection -> {
            var existing = find(connection, requested.operationId());
            if (existing.isPresent()) {
                var saved = existing.get();
                if (!saved.plate().equals(requested.plate()) || saved.heightCm() != requested.heightCm()) {
                    throw new DomainException("Gate operation ID already belongs to a different request.");
                }
                if (saved.status() == GateAdmission.Status.CANCELLED) {
                    throw new DomainException("A cancelled gate permit cannot be reused.");
                }
                return saved;
            }
            try (var statement = connection.prepareStatement("INSERT INTO gate_admissions VALUES (?, ?, ?, ?)")) {
                statement.setString(1, requested.operationId());
                statement.setString(2, requested.plate());
                statement.setInt(3, requested.heightCm());
                statement.setString(4, requested.status().name());
                statement.executeUpdate();
            }
            return requested;
        });
    }

    public void admit(String operationId) {
        database.transaction(connection -> {
            var admission = find(connection, operationId)
                    .orElseThrow(() -> new DomainException("No gate permit exists for " + operationId));
            if (admission.status() != GateAdmission.Status.ADMITTED) {
                update(connection, admission.admit());
            }
            return null;
        });
    }

    public void cancel(String operationId) {
        database.transaction(connection -> {
            var admission = find(connection, operationId);
            if (admission.isPresent() && admission.get().status() != GateAdmission.Status.CANCELLED) {
                update(connection, admission.get().cancel());
            }
            return null;
        });
    }

    public Optional<GateAdmission> find(String operationId) {
        return database.transaction(connection -> find(connection, operationId));
    }

    public List<GateAdmission> all() {
        return database.transaction(connection -> {
            List<GateAdmission> result = new ArrayList<>();
            try (var statement = connection.prepareStatement("SELECT * FROM gate_admissions ORDER BY operation_id");
                 var rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(read(rows));
                }
            }
            return List.copyOf(result);
        });
    }

    private Optional<GateAdmission> find(Connection connection, String id) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM gate_admissions WHERE operation_id = ?")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        }
    }

    private GateAdmission read(ResultSet rows) throws SQLException {
        return new GateAdmission(rows.getString("operation_id"), rows.getString("plate"),
                rows.getInt("height_cm"), GateAdmission.Status.valueOf(rows.getString("status")));
    }

    private void update(Connection connection, GateAdmission admission) throws SQLException {
        try (var statement = connection.prepareStatement("UPDATE gate_admissions SET status = ? WHERE operation_id = ?")) {
            statement.setString(1, admission.status().name());
            statement.setString(2, admission.operationId());
            statement.executeUpdate();
        }
    }
}
