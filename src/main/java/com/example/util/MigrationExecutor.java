package com.example.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class MigrationExecutor {

    private static final Logger LOGGER = Logger.getLogger(MigrationExecutor.class.getName());
    private final String jdbcUrl;
    private final String username;
    private final String password;

    public MigrationExecutor(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    public void executeMigrations() throws IOException, SQLException {
        executeMigration("db/migration/v1_0_init.sql");
        executeMigration("db/migration/v2_0_add_description.sql");
    }

    public void executeMigration(String scriptPath) throws IOException, SQLException {
        String sql;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(scriptPath)) {
            if (input == null) {
                throw new IOException("Script de migration introuvable : " + scriptPath);
            }
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.stripLeading().startsWith("--"))
                    .collect(Collectors.joining("\n"));
        }

        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS schema_migrations ("
                        + "script VARCHAR(255) PRIMARY KEY, "
                        + "applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            }
            try (PreparedStatement check = connection.prepareStatement(
                    "SELECT COUNT(*) FROM schema_migrations WHERE script = ?")) {
                check.setString(1, scriptPath);
                try (ResultSet result = check.executeQuery()) {
                    result.next();
                    if (result.getInt(1) > 0) {
                        LOGGER.info("Migration déjà appliquée : " + scriptPath);
                        return;
                    }
                }
            }

            // MySQL valide les DDL implicitement : une transaction ne les annule pas.
            // Les vérifications permettent de reprendre une migration partiellement appliquée.
            for (String instruction : sql.split(";")) {
                instruction = instruction.trim();
                if (instruction.isEmpty() || dejaPresente(connection, instruction)) {
                    continue;
                }
                try (Statement statement = connection.createStatement()) {
                    statement.execute(instruction);
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO schema_migrations(script) VALUES (?)")) {
                insert.setString(1, scriptPath);
                insert.executeUpdate();
            }
            LOGGER.info("Migration appliquée : " + scriptPath);
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Échec de la migration : " + scriptPath, e);
            throw e;
        }
    }

    private boolean dejaPresente(Connection connection, String instruction) throws SQLException {
        if (instruction.startsWith("ALTER TABLE salles ADD COLUMN description")) {
            try (ResultSet columns = connection.getMetaData().getColumns(
                    connection.getCatalog(), null, "salles", "description")) {
                return columns.next();
            }
        }
        if (instruction.startsWith("CREATE UNIQUE INDEX idx_utilisateur_email")) {
            try (ResultSet indexes = connection.getMetaData().getIndexInfo(
                    connection.getCatalog(), null, "utilisateurs", true, false)) {
                while (indexes.next()) {
                    if ("idx_utilisateur_email".equalsIgnoreCase(indexes.getString("INDEX_NAME"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
