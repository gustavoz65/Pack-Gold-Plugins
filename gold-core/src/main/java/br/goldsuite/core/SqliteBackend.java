package br.goldsuite.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Optional;

/** Acesso interno ao disco: chamado somente pelas AsyncTasks do GoldCore. */
final class SqliteBackend {
    private final Path file;

    SqliteBackend(Path file) { this.file = file; }

    void initialize() throws Exception {
        Files.createDirectories(this.file.getParent());
        Class.forName("org.sqlite.JDBC");
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("CREATE TABLE IF NOT EXISTS gold_records (namespace TEXT NOT NULL, record_key TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(namespace, record_key))");
        }
    }

    private Connection open() throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + this.file.toAbsolutePath());
        try (Statement statement = connection.createStatement()) { statement.execute("PRAGMA busy_timeout=5000"); }
        return connection;
    }

    private void validate(String namespace, String key) {
        if (namespace == null || !namespace.matches("[a-z0-9_-]{1,40}") || key == null || key.length() > 128) {
            throw new IllegalArgumentException("Espaco de dados ou chave invalida.");
        }
    }

    Optional<String> get(String namespace, String key) throws Exception {
        validate(namespace, key);
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("SELECT value FROM gold_records WHERE namespace=? AND record_key=?")) {
            statement.setString(1, namespace);
            statement.setString(2, key);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? Optional.of(rows.getString(1)) : Optional.empty(); }
        }
    }

    void put(String namespace, String key, String value) throws Exception {
        validate(namespace, key);
        if (value == null || value.length() > 1024 * 1024) { throw new IllegalArgumentException("Valor ausente ou excessivo."); }
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("INSERT OR REPLACE INTO gold_records(namespace, record_key, value) VALUES(?,?,?)")) {
            statement.setString(1, namespace);
            statement.setString(2, key);
            statement.setString(3, value);
            statement.executeUpdate();
        }
    }

    void delete(String namespace, String key) throws Exception {
        validate(namespace, key);
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("DELETE FROM gold_records WHERE namespace=? AND record_key=?")) {
            statement.setString(1, namespace);
            statement.setString(2, key);
            statement.executeUpdate();
        }
    }
}
