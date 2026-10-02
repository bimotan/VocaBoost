package com.vocabtrainer.repository;

import com.vocabtrainer.util.DateTimeUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

public class DictionaryCacheRepository {
    private final DatabaseManager databaseManager;

    public DictionaryCacheRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    /**
     * A cached lookup: the serialized entries, the dictionary they came from and when they were
     * saved ({@code null} when the time cannot be read, so the entry counts as expired).
     */
    public record CachedLookup(String payload, String source, LocalDateTime createdAt) {
    }

    public Optional<CachedLookup> find(String english) throws SQLException {
        String sql = "SELECT payload, source, created_at FROM dictionary_cache WHERE english = ? COLLATE NOCASE";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, english.trim());
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new CachedLookup(rs.getString("payload"), rs.getString("source"),
                        parseTime(rs.getString("created_at"))));
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

    public void delete(String english) throws SQLException {
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM dictionary_cache WHERE english = ? COLLATE NOCASE")) {
            statement.setString(1, english.trim());
            statement.executeUpdate();
        }
    }

    private static LocalDateTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (DateTimeFormatter format : List.of(DateTimeUtil.ISO_FORMATTER, DateTimeUtil.LEGACY_FORMATTER)) {
            try {
                return LocalDateTime.parse(value.trim(), format);
            } catch (DateTimeParseException e) {
                // Try the next format.
            }
        }
        return null;
    }
}
