package com.vocabtrainer.repository;

import com.vocabtrainer.util.DateTimeUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Optional;

public class DictionaryCacheRepository {
    private final DatabaseManager databaseManager;

    public DictionaryCacheRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public Optional<String> findPayload(String english) throws SQLException {
        String sql = "SELECT payload FROM dictionary_cache WHERE english = ? COLLATE NOCASE";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, english.trim());
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rs.getString("payload"));
                }
            }
        }
        return Optional.empty();
    }

    public void save(String english, String payload, String source, LocalDateTime now) throws SQLException {
        String sql = """
            INSERT INTO dictionary_cache(english, payload, source, created_at)
            VALUES(?, ?, ?, ?)
            ON CONFLICT(english) DO UPDATE SET
                payload = excluded.payload,
                source = excluded.source,
                created_at = excluded.created_at
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, english.trim());
            statement.setString(2, payload);
            statement.setString(3, source);
            statement.setString(4, DateTimeUtil.toDatabase(now));
            statement.executeUpdate();
        }
    }
}
