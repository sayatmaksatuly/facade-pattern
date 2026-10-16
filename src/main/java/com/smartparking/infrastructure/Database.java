package com.smartparking.infrastructure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** One local connection. Each gateway call commits its own transaction, never the entire chain. */
public final class Database implements AutoCloseable {
    private final Connection connection;

    @FunctionalInterface
    public interface SqlWork<T> { T run(Connection connection) throws SQLException; }

    public Database(Path file) {
        Connection opened = null;
        try {
            Path absolute = file.toAbsolutePath().normalize();
            Files.createDirectories(absolute.getParent());
            opened = DriverManager.getConnection("jdbc:sqlite:" + absolute);
            try (var statement = opened.createStatement()) {
                statement.execute("PRAGMA busy_timeout = 5000");
                statement.execute("PRAGMA foreign_keys = ON");
                statement.execute("PRAGMA journal_mode = WAL");
                statement.execute("PRAGMA synchronous = FULL");
            }
            opened.setAutoCommit(false);
            connection = opened;
        } catch (IOException | SQLException failure) {
            if (opened != null) {
                try { opened.close(); } catch (SQLException closing) { failure.addSuppressed(closing); }
            }
            throw new StoreException("Cannot open SQLite database: " + file, failure);
        }
    }

    public synchronized <T> T transaction(SqlWork<T> work) {
        try {
            T result = work.run(connection);
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException | Error failure) {
            try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
            if (failure instanceof SQLException sql) {
                throw new StoreException("SQLite transaction failed: " + sql.getMessage(), sql);
            }
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw (Error) failure;
        }
    }

    @Override public synchronized void close() {
        try { connection.close(); }
        catch (SQLException failure) { throw new StoreException("Cannot close SQLite database", failure); }
    }
}
