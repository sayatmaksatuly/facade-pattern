package com.smartparking.payment;

import com.smartparking.infrastructure.Database;
import com.smartparking.infrastructure.DomainException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The local payment ledger is the payment side effect; no external bank is used. */
public final class PaymentGateway {
    private final Database database;

    public PaymentGateway(Database database) {
        this.database = database;
        database.transaction(connection -> {
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS payments (
                      operation_id TEXT PRIMARY KEY, hours INTEGER NOT NULL CHECK(hours BETWEEN 1 AND 24),
                      tender_cents INTEGER NOT NULL CHECK(tender_cents >= amount_cents),
                      amount_cents INTEGER NOT NULL CHECK(amount_cents = hours * 500),
                      status TEXT NOT NULL CHECK(status IN ('AUTHORIZED','CAPTURED','REFUNDED')))
                    """);
            }
            return null;
        });
    }

    public ParkingPayment save(ParkingPayment requested, boolean capture) {
        return database.transaction(connection -> {
            var existing = find(connection, requested.operationId());
            ParkingPayment payment = requested;
            if (existing.isPresent()) {
                payment = existing.get();
                if (payment.hours() != requested.hours() || payment.tenderCents() != requested.tenderCents()) {
                    throw new DomainException("Payment operation ID already belongs to a different request.");
                }
                if (payment.status() == ParkingPayment.Status.REFUNDED) {
                    throw new DomainException("A refunded payment cannot be reused.");
                }
            }
            if (capture && payment.status() == ParkingPayment.Status.AUTHORIZED) {
                payment = payment.capture();
            }
            if (existing.isEmpty()) {
                try (var statement = connection.prepareStatement("INSERT INTO payments VALUES (?, ?, ?, ?, ?)")) {
                    statement.setString(1, payment.operationId());
                    statement.setInt(2, payment.hours());
                    statement.setInt(3, payment.tenderCents());
                    statement.setInt(4, payment.amountCents());
                    statement.setString(5, payment.status().name());
                    statement.executeUpdate();
                }
            } else if (payment.status() != existing.get().status()) {
                update(connection, payment);
            }
            return payment;
        });
    }

    public void capture(String operationId) {
        database.transaction(connection -> {
            var payment = find(connection, operationId)
                    .orElseThrow(() -> new DomainException("No payment exists for " + operationId));
            if (payment.status() != ParkingPayment.Status.CAPTURED) {
                update(connection, payment.capture());
            }
            return null;
        });
    }

    public void refund(String operationId) {
        database.transaction(connection -> {
            var payment = find(connection, operationId);
            if (payment.isPresent() && payment.get().status() != ParkingPayment.Status.REFUNDED) {
                update(connection, payment.get().refund());
            }
            return null;
        });
    }

    public Optional<ParkingPayment> find(String operationId) {
        return database.transaction(connection -> find(connection, operationId));
    }

    public List<ParkingPayment> all() {
        return database.transaction(connection -> {
            List<ParkingPayment> result = new ArrayList<>();
            try (var statement = connection.prepareStatement("SELECT * FROM payments ORDER BY operation_id");
                 var rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(read(rows));
                }
            }
            return List.copyOf(result);
        });
    }

    private Optional<ParkingPayment> find(Connection connection, String id) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM payments WHERE operation_id = ?")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        }
    }

    private ParkingPayment read(ResultSet rows) throws SQLException {
        return new ParkingPayment(rows.getString("operation_id"), rows.getInt("hours"), rows.getInt("tender_cents"),
                rows.getInt("amount_cents"), ParkingPayment.Status.valueOf(rows.getString("status")));
    }

    private void update(Connection connection, ParkingPayment payment) throws SQLException {
        try (var statement = connection.prepareStatement("UPDATE payments SET status = ? WHERE operation_id = ?")) {
            statement.setString(1, payment.status().name());
            statement.setString(2, payment.operationId());
            statement.executeUpdate();
        }
    }
}
