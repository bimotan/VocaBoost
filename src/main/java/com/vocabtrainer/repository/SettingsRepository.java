package com.vocabtrainer.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

public class SettingsRepository {
    private final DatabaseManager databaseManager;

    public SettingsRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public Optional<String> find(String key) throws SQLException {
        String sql = "SELECT value FROM settings WHERE key = ?";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return Optional.ofNullable(rs.getString("value"));
                }
            }
        }
        return Optional.empty();
    }

    public void save(String key, String value) throws SQLException {
        String sql = """
            INSERT INTO settings(key, value)
            VALUES(?, ?)
            ON CONFLICT(key) DO UPDATE SET value = excluded.value
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
        }
    }

    public void delete(String key) throws SQLException {
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM settings WHERE key = ?")) {
            statement.setString(1, key);
            statement.executeUpdate();
        }
    }
}
