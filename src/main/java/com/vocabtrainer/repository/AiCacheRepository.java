package com.vocabtrainer.repository;

import com.vocabtrainer.util.DateTimeUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Optional;

public class AiCacheRepository {
    private final DatabaseManager databaseManager;

    public AiCacheRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public Optional<String> find(String cacheKey) throws SQLException {
        String sql = "SELECT response FROM ai_cache WHERE cache_key = ?";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, cacheKey);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return Optional.ofNullable(rs.getString("response"));
                }
            }
        }
        return Optional.empty();
    }

    public void save(String cacheKey, String response, LocalDateTime createdAt) throws SQLException {
        String sql = """
            INSERT INTO ai_cache(cache_key, response, created_at)
            VALUES(?, ?, ?)
            ON CONFLICT(cache_key) DO UPDATE SET
                response = excluded.response,
                created_at = excluded.created_at
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, cacheKey);
            statement.setString(2, response);
            statement.setString(3, DateTimeUtil.toDatabase(createdAt));
            statement.executeUpdate();
        }
    }
}
