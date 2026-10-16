package com.smartparking.workflow;

import com.smartparking.infrastructure.Database;
import com.smartparking.infrastructure.DomainException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Journal only; domain tables remain owned by their independent subsystem gateways. */
public final class WorkflowStore {
    private final Database database;

    public WorkflowStore(Database database) {
        this.database = database;
        database.transaction(c -> {
            try (var s = c.createStatement()) {
                s.execute("""
                    CREATE TABLE IF NOT EXISTS workflows (
                      operation_id TEXT PRIMARY KEY, plate TEXT NOT NULL, height_cm INTEGER NOT NULL,
                      space INTEGER NOT NULL, hours INTEGER NOT NULL, tender_cents INTEGER NOT NULL,
                      state TEXT NOT NULL, last_error TEXT NOT NULL DEFAULT '')
                    """);
                s.execute("""
                    CREATE TABLE IF NOT EXISTS workflow_events (
                      sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                      operation_id TEXT NOT NULL REFERENCES workflows(operation_id),
                      event_key TEXT NOT NULL, message TEXT NOT NULL,
                      UNIQUE(operation_id, event_key))
                    """);
            }
            return null;
        });
    }

    public Workflow createOrLoad(ParkingRequest request) {
        return database.transaction(c -> {
            Optional<Workflow> existing = find(c, request.operationId());
            if (existing.isPresent()) {
                if (!existing.get().request().equals(request)) {
                    throw new DomainException("Operation ID already belongs to different input.");
                }
                return existing.get();
            }
            try (var s = c.prepareStatement("""
                INSERT INTO workflows(operation_id, plate, height_cm, space, hours, tender_cents, state)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING_A')
                """)) {
                s.setString(1, request.operationId()); s.setString(2, request.plate());
                s.setInt(3, request.heightCm()); s.setInt(4, request.space());
                s.setInt(5, request.hours()); s.setInt(6, request.tenderCents());
                s.executeUpdate();
            }
            append(c, request.operationId(), "REQUESTED", "Request persisted; waiting for A.");
            return new Workflow(request, WorkflowState.PENDING_A, "");
        });
    }

    public Workflow get(String id) {
        return find(id).orElseThrow(() -> new DomainException("Unknown workflow: " + id));
    }

    public Optional<Workflow> find(String id) {
        return database.transaction(c -> find(c, id));
    }

    public List<Workflow> all() {
        return database.transaction(c -> {
            List<Workflow> results = new ArrayList<>();
            try (var s = c.prepareStatement("SELECT * FROM workflows ORDER BY rowid");
                 var rows = s.executeQuery()) {
                while (rows.next()) results.add(read(rows));
            }
            return List.copyOf(results);
        });
    }

    /** The progress marker and its journal entry are committed together. */
    public void transition(String id, WorkflowState state, String error, String key, String message) {
        database.transaction(c -> {
            try (var s = c.prepareStatement("UPDATE workflows SET state=?, last_error=? WHERE operation_id=?")) {
                s.setString(1, state.name()); s.setString(2, error); s.setString(3, id);
                if (s.executeUpdate() != 1) throw new DomainException("Unknown workflow: " + id);
            }
            append(c, id, key, message);
            return null;
        });
    }

    public void event(String id, String key, String message) {
        database.transaction(c -> { append(c, id, key, message); return null; });
    }

    public List<WorkflowEvent> events(String id) {
        return database.transaction(c -> {
            List<WorkflowEvent> result = new ArrayList<>();
            try (var s = c.prepareStatement("SELECT * FROM workflow_events WHERE operation_id=? ORDER BY sequence")) {
                s.setString(1, id);
                try (var rows = s.executeQuery()) {
                    while (rows.next()) result.add(new WorkflowEvent(rows.getLong("sequence"), id,
                            rows.getString("event_key"), rows.getString("message")));
                }
            }
            return List.copyOf(result);
        });
    }

    private static void append(Connection c, String id, String key, String message) throws SQLException {
        try (var s = c.prepareStatement("""
            INSERT INTO workflow_events(operation_id,event_key,message) VALUES (?,?,?)
            ON CONFLICT(operation_id,event_key) DO NOTHING
            """)) {
            s.setString(1, id); s.setString(2, key); s.setString(3, message); s.executeUpdate();
        }
    }

    private static Optional<Workflow> find(Connection c, String id) throws SQLException {
        try (var s = c.prepareStatement("SELECT * FROM workflows WHERE operation_id=?")) {
            s.setString(1, id);
            try (var rows = s.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        }
    }

    private static Workflow read(ResultSet rows) throws SQLException {
        return new Workflow(new ParkingRequest(rows.getString("operation_id"), rows.getString("plate"),
                rows.getInt("height_cm"), rows.getInt("space"), rows.getInt("hours"),
                rows.getInt("tender_cents")), WorkflowState.valueOf(rows.getString("state")),
                rows.getString("last_error"));
    }
}
