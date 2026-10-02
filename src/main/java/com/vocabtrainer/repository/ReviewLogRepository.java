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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /** Every review log of the deck's words, archived words included, oldest first. */
    public List<ReviewLog> findByDeck(long deckId) throws SQLException {
        String sql = """
            SELECT l.*
            FROM review_logs l
            JOIN words w ON w.id = l.word_id
            WHERE w.deck_id = ?
            ORDER BY l.reviewed_at, l.id
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, deckId);
            try (ResultSet rs = statement.executeQuery()) {
                List<ReviewLog> logs = new ArrayList<>();
                while (rs.next()) {
                    logs.add(new ReviewLog(
                        rs.getLong("id"),
                        rs.getLong("word_id"),
                        DateTimeUtil.fromDatabase(rs.getString("reviewed_at")),
                        rs.getString("user_answer"),
                        rs.getString("correct_answer"),
                        rs.getDouble("similarity"),
                        ReviewRating.valueOf(rs.getString("rating")),
                        rs.getLong("elapsed_millis")
                    ));
                }
                return logs;
            }
        }
    }

    /** Every review log in every deck. */
    public int countAll() throws SQLException {
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM review_logs");
             ResultSet rs = statement.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    public int countSince(LocalDateTime since) throws SQLException {
        return scalarInt("SELECT COUNT(*) FROM review_logs WHERE reviewed_at >= ?", since);
    }

    public int countCorrectSince(LocalDateTime since) throws SQLException {
        return scalarInt("SELECT COUNT(*) FROM review_logs WHERE reviewed_at >= ? AND rating <> 'AGAIN'", since);
    }

    public int countSince(long deckId, LocalDateTime since) throws SQLException {
        return scalarInt("""
            SELECT COUNT(*)
            FROM review_logs l
            JOIN words w ON w.id = l.word_id
            WHERE w.deck_id = ? AND l.reviewed_at >= ?
            """, deckId, since);
    }

    public int countCorrectSince(long deckId, LocalDateTime since) throws SQLException {
        return scalarInt("""
            SELECT COUNT(*)
            FROM review_logs l
            JOIN words w ON w.id = l.word_id
            WHERE w.deck_id = ? AND l.reviewed_at >= ? AND l.rating <> 'AGAIN'
            """, deckId, since);
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

    /** The time of the newest review of every deck that has reviews, by deck id, in one query. */
    public Map<Long, LocalDateTime> latestReviewByDeck() throws SQLException {
        String sql = """
            SELECT w.deck_id, MAX(l.reviewed_at) AS latest
            FROM review_logs l
            JOIN words w ON w.id = l.word_id
            GROUP BY w.deck_id
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            Map<Long, LocalDateTime> latest = new HashMap<>();
            while (rs.next()) {
                latest.put(rs.getLong("deck_id"), DateTimeUtil.fromDatabase(rs.getString("latest")));
            }
            return latest;
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

    private int scalarInt(String sql, long deckId, LocalDateTime since) throws SQLException {
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, deckId);
            statement.setString(2, DateTimeUtil.toDatabase(since));
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }
}
