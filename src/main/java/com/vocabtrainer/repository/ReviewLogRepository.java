package com.vocabtrainer.repository;

import com.vocabtrainer.domain.ReviewLog;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.util.DateTimeUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;

public class ReviewLogRepository {
    private final DatabaseManager databaseManager;

    public ReviewLogRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public ReviewLog insert(ReviewLog log) throws SQLException {
        String sql = """
            INSERT INTO review_logs(word_id, reviewed_at, user_answer, correct_answer, similarity, rating, elapsed_millis)
            VALUES(?, ?, ?, ?, ?, ?, ?)
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, log.getWordId());
            statement.setString(2, DateTimeUtil.toDatabase(log.getReviewedAt()));
            statement.setString(3, log.getUserAnswer());
            statement.setString(4, log.getCorrectAnswer());
            statement.setDouble(5, log.getSimilarity());
            statement.setString(6, log.getRating().name());
            statement.setLong(7, log.getElapsedMillis());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    log.setId(keys.getLong(1));
                }
            }
        }
        return log;
    }

    public int countSince(LocalDateTime since) throws SQLException {
        return scalarInt("SELECT COUNT(*) FROM review_logs WHERE reviewed_at >= ?", since);
    }

    public int countCorrectSince(LocalDateTime since) throws SQLException {
        return scalarInt("SELECT COUNT(*) FROM review_logs WHERE reviewed_at >= ? AND rating <> 'AGAIN'", since);
    }

    public int countByRating(ReviewRating rating) throws SQLException {
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM review_logs WHERE rating = ?")) {
            statement.setString(1, rating.name());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private int scalarInt(String sql, LocalDateTime since) throws SQLException {
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, DateTimeUtil.toDatabase(since));
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }
}