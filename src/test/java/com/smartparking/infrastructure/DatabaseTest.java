package com.smartparking.infrastructure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** Verify actual SQLite transaction guarantees used by every independent gateway. */
class DatabaseTest {
    @TempDir Path directory;

    private void createLedger(Database database) {
        database.transaction(connection -> {
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE ledger (id INTEGER PRIMARY KEY)");
            }
            return null;
        });
    }

    private int committedRows(Database database) {
        return database.transaction(connection -> {
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT COUNT(*) FROM ledger")) {
                rows.next();
                return rows.getInt(1);
            }
        });
    }

    @Test void aBusinessFailureRollsBackOnlyItsTransactionAndPreservesEarlierCommits() {
        Path file = directory.resolve("transactions.db");
        try (var database = new Database(file)) {
            createLedger(database);
            database.transaction(connection -> {
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("INSERT INTO ledger VALUES (1)");
                }
                return null;
            });
            var expected = new DomainException("Device rejected a pending change");
            var actual = assertThrows(DomainException.class, () -> database.transaction(connection -> {
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("INSERT INTO ledger VALUES (2)");
                }
                throw expected;
            }));
            assertSame(expected, actual);
            assertEquals(1, committedRows(database));
        }
        try (var reopened = new Database(file)) {
            assertEquals(1, committedRows(reopened));
        }
    }

    @Test void anSqlFailureRollsBackEarlierWritesWithinTheSameTransaction() {
        try (var database = new Database(directory.resolve("sql-failure.db"))) {
            createLedger(database);
            var failure = assertThrows(StoreException.class, () -> database.transaction(connection -> {
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("INSERT INTO ledger VALUES (1)");
                    statement.executeUpdate("INSERT INTO ledger VALUES (1)");
                }
                return null;
            }));
            assertInstanceOf(java.sql.SQLException.class, failure.getCause());
            assertEquals(0, committedRows(database));
        }
    }

    @Test void anUnexpectedErrorAlsoRollsBackUncommittedChanges() {
        try (var database = new Database(directory.resolve("error.db"))) {
            createLedger(database);
            var error = new AssertionError("Unexpected device failure");
            assertSame(error, assertThrows(AssertionError.class, () -> database.transaction(connection -> {
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("INSERT INTO ledger VALUES (1)");
                }
                throw error;
            })));
            assertEquals(0, committedRows(database));
        }
    }

    @Test void aClosedStoreReportsItsFailureWithoutRecreatingOrErasingTheFile() {
        Path file = directory.resolve("closed.db");
        var database = new Database(file);
        createLedger(database);
        database.close();
        var failure = assertThrows(StoreException.class, () -> committedRows(database));
        assertInstanceOf(java.sql.SQLException.class, failure.getCause());
        assertTrue(Files.exists(file));
        try (var reopened = new Database(file)) {
            assertEquals(0, committedRows(reopened));
        }
    }

    @Test void anInvalidStoreLocationProducesAnActionableStoreException() throws IOException {
        Path regularFile = directory.resolve("not-a-directory");
        Files.writeString(regularFile, "preserve this file");
        var failure = assertThrows(StoreException.class, () -> new Database(regularFile.resolve("garage.db")));
        assertTrue(failure.getMessage().contains("Cannot open SQLite database"));
        assertInstanceOf(IOException.class, failure.getCause());
        assertEquals("preserve this file", Files.readString(regularFile));
    }

    @Test void aCorruptDatabaseIsRejectedWithoutOverwritingItsContents() throws IOException {
        Path file = directory.resolve("corrupt.db");
        String contents = "This is deliberately not a SQLite database.";
        Files.writeString(file, contents);
        var failure = assertThrows(StoreException.class, () -> new Database(file));
        assertInstanceOf(java.sql.SQLException.class, failure.getCause());
        assertEquals(contents, Files.readString(file));
    }
}
