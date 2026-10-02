package com.vocabtrainer.repository;

import com.vocabtrainer.domain.HardWordStat;
import com.vocabtrainer.domain.ReviewLog;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.util.DateTimeUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ReviewLogRepository {
    private final DatabaseManager databaseManager;

    /** Reviews on one day, and how many of them were not rated Again. */
    public record DailyCount(LocalDate day, int reviews, int correct) {
    }

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

    /**
     * Review counts per day since {@code since}, oldest day first; days without reviews are left
     * out. A {@code deckId} of 0 or less counts every deck.
     */
    public List<DailyCount> dailyCounts(long deckId, LocalDateTime since) throws SQLException {
        String sql = deckId <= 0 ? """
            SELECT substr(reviewed_at, 1, 10) AS day,
                   COUNT(*) AS reviews,
                   SUM(CASE WHEN rating <> 'AGAIN' THEN 1 ELSE 0 END) AS correct
            FROM review_logs
            WHERE reviewed_at >= ?
            GROUP BY day
            ORDER BY day
            """ : """
            SELECT substr(l.reviewed_at, 1, 10) AS day,
                   COUNT(*) AS reviews,
                   SUM(CASE WHEN l.rating <> 'AGAIN' THEN 1 ELSE 0 END) AS correct
            FROM review_logs l
            JOIN words w ON w.id = l.word_id
            WHERE w.deck_id = ? AND l.reviewed_at >= ?
            GROUP BY day
            ORDER BY day
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            if (deckId > 0) {
                statement.setLong(index++, deckId);
            }
            statement.setString(index, DateTimeUtil.toDatabase(since));
            try (ResultSet rs = statement.executeQuery()) {
                List<DailyCount> counts = new ArrayList<>();
                while (rs.next()) {
                    counts.add(new DailyCount(DateTimeUtil.dateFromDatabase(rs.getString("day")), rs.getInt("reviews"),
                        rs.getInt("correct")));
                }
                return counts;
            }
        }
    }

    /**
     * The deck's active words with the lowest average answer similarity, then the most Again
     * ratings and the most reviews. Words never reviewed are left out.
     */
    public List<HardWordStat> hardestWords(long deckId, int limit) throws SQLException {
        String sql = """
            SELECT w.english, w.chinese, COUNT(l.id) AS reviews,
                   AVG(l.similarity) AS avg_similarity,
                   SUM(CASE WHEN l.rating = 'AGAIN' THEN 1 ELSE 0 END) AS again_count
            FROM words w
            JOIN review_logs l ON l.word_id = w.id
            WHERE w.deck_id = ? AND w.archived = 0
            GROUP BY w.id
            ORDER BY avg_similarity ASC, again_count DESC, reviews DESC
            LIMIT ?
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, deckId);
            statement.setInt(2, limit);
            try (ResultSet rs = statement.executeQuery()) {
                List<HardWordStat> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(new HardWordStat(
                        rs.getString("english"),
                        rs.getString("chinese"),
                        rs.getInt("reviews"),
                        rs.getDouble("avg_similarity"),
                        rs.getInt("again_count")
                    ));
                }
                return result;
            }
        }
    }

    /** When a word of the deck, archived words included, was last reviewed. */
    public Optional<LocalDateTime> latestReviewAt(long deckId) throws SQLException {
        String sql = """
            SELECT MAX(l.reviewed_at)
            FROM review_logs l
            JOIN words w ON w.id = l.word_id
            WHERE w.deck_id = ?
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, deckId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.ofNullable(DateTimeUtil.fromDatabase(rs.getString(1))) : Optional.empty();
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
