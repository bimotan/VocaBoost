package com.vocabtrainer.repository;

import com.vocabtrainer.domain.HardWordStat;
import com.vocabtrainer.domain.ReviewKind;
import com.vocabtrainer.domain.ReviewLog;
import com.vocabtrainer.domain.ReviewMode;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ReviewLogRepository {
    /**
     * {@link ReviewLog#isCorrect()} in SQL, for a review_logs row aliased {@code l}: the effective
     * rating is not Again, where a log without one counts as its rating capped by its similarity
     * (below {@value ReviewRating#MIN_SIMILARITY_HARD} it counts as Again).
     */
    static final String CORRECT = "(CASE WHEN l.effective_rating IS NOT NULL THEN l.effective_rating <> 'AGAIN'"
        + " ELSE l.rating <> 'AGAIN' AND l.similarity >= " + ReviewRating.MIN_SIMILARITY_HARD + " END)";

    private final DatabaseManager databaseManager;

    /** Reviews on one day, and how many of them were correct ({@link ReviewLog#isCorrect()}). */
    public record DailyCount(LocalDate day, int reviews, int correct) {
    }

    public ReviewLogRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public ReviewLog insert(ReviewLog log) throws SQLException {
        String sql = """
            INSERT INTO review_logs(word_id, reviewed_at, user_answer, correct_answer, similarity, rating, elapsed_millis,
                                    kind, direction, effective_rating, overridden)
            VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bindLog(statement, log);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    log.setId(keys.getLong(1));
                }
            }
        }
        return log;
    }

    /**
     * Inserts the logs, in order, except those whose word already has a log with the same time and
     * rating: that is the same review, for example from restoring a backup twice or listed twice in
     * one file. All or none are written; joins the caller's transaction if there is one. Returns how
     * many were inserted; the ids of the logs are not set.
     */
    public int insertAllIfAbsent(List<ReviewLog> logs) throws SQLException {
        if (logs == null || logs.isEmpty()) {
            return 0;
        }
        String sql = """
            INSERT INTO review_logs(word_id, reviewed_at, user_answer, correct_answer, similarity, rating, elapsed_millis,
                                    kind, direction, effective_rating, overridden)
            SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
            WHERE NOT EXISTS (SELECT 1 FROM review_logs WHERE word_id = ? AND reviewed_at = ? AND rating = ?)
            """;
        return databaseManager.inTransaction(() -> {
            try (Connection connection = databaseManager.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                int inserted = 0;
                for (ReviewLog log : logs) {
                    bindLog(statement, log);
                    statement.setLong(12, log.getWordId());
                    statement.setString(13, DateTimeUtil.toDatabase(log.getReviewedAt()));
                    statement.setString(14, log.getRating().name());
                    inserted += statement.executeUpdate();
                }
                return inserted;
            }
        });
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
                return mapLogs(rs);
            }
        }
    }

    /** The word's review logs, oldest first. */
    public List<ReviewLog> findByWord(long wordId) throws SQLException {
        String sql = "SELECT * FROM review_logs WHERE word_id = ? ORDER BY reviewed_at, id";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, wordId);
            try (ResultSet rs = statement.executeQuery()) {
                return mapLogs(rs);
            }
        }
    }

    /**
     * The review logs of the words stored without a card state (see
     * {@code WordRepository.findWithoutCardState}), by word, each word's oldest first.
     */
    public List<ReviewLog> findOfWordsWithoutCardState() throws SQLException {
        String sql = """
            SELECT * FROM review_logs
            WHERE word_id IN (SELECT id FROM words WHERE card_state IS NULL)
            ORDER BY word_id, reviewed_at, id
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            return mapLogs(rs);
        }
    }

    /** Binds the eleven inserted columns, word id to overridden. */
    private static void bindLog(PreparedStatement statement, ReviewLog log) throws SQLException {
        statement.setLong(1, log.getWordId());
        statement.setString(2, DateTimeUtil.toDatabase(log.getReviewedAt()));
        statement.setString(3, log.getUserAnswer());
        statement.setString(4, log.getCorrectAnswer());
        statement.setDouble(5, log.getSimilarity());
        statement.setString(6, log.getRating().name());
        statement.setLong(7, log.getElapsedMillis());
        statement.setString(8, log.getKind().name());
        statement.setString(9, log.getDirection() == null ? null : log.getDirection().name());
        ReviewRating effective = log.getRecordedEffectiveRating();
        statement.setString(10, effective == null ? null : effective.name());
        statement.setInt(11, log.isOverridden() ? 1 : 0);
    }

    private static List<ReviewLog> mapLogs(ResultSet rs) throws SQLException {
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
                rs.getLong("elapsed_millis"),
                kind(rs.getString("kind")),
                direction(rs.getString("direction")),
                effectiveRating(rs.getString("effective_rating")),
                rs.getInt("overridden") != 0
            ));
        }
        return logs;
    }

    /** The stored kind; one this version does not know reads as a review. */
    private static ReviewKind kind(String value) {
        try {
            return value == null ? ReviewKind.REVIEW : ReviewKind.valueOf(value);
        } catch (IllegalArgumentException e) {
            return ReviewKind.REVIEW;
        }
    }

    /** The stored effective rating, or null when the log has none (or one this version does not know). */
    private static ReviewRating effectiveRating(String value) {
        if (value == null) {
            return null;
        }
        try {
            return ReviewRating.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The stored direction, or null when unknown. */
    private static ReviewMode direction(String value) {
        if (ReviewMode.EN_TO_ZH.name().equals(value)) {
            return ReviewMode.EN_TO_ZH;
        }
        return ReviewMode.ZH_TO_EN.name().equals(value) ? ReviewMode.ZH_TO_EN : null;
    }

    /**
     * How many new cards of the deck were introduced (had their first review, {@link ReviewKind#LEARN})
     * since {@code since}, the start of the study day; the new-cards-per-day limit counts them.
     */
    public int countNewCardsIntroducedSince(long deckId, LocalDateTime since) throws SQLException {
        return scalarInt("""
            SELECT COUNT(*)
            FROM review_logs l
            JOIN words w ON w.id = l.word_id
            WHERE w.deck_id = ? AND l.reviewed_at >= ? AND l.kind = 'LEARN'
            """, deckId, since);
    }

    /** {@link #countNewCardsIntroducedSince} of every deck that introduced new cards, by deck id, in one query. */
    public Map<Long, Integer> newCardsIntroducedByDeckSince(LocalDateTime since) throws SQLException {
        String sql = """
            SELECT w.deck_id, COUNT(*) AS introduced
            FROM review_logs l
            JOIN words w ON w.id = l.word_id
            WHERE l.reviewed_at >= ? AND l.kind = 'LEARN'
            GROUP BY w.deck_id
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, DateTimeUtil.toDatabase(since));
            try (ResultSet rs = statement.executeQuery()) {
                Map<Long, Integer> introduced = new HashMap<>();
                while (rs.next()) {
                    introduced.put(rs.getLong("deck_id"), rs.getInt("introduced"));
                }
                return introduced;
            }
        }
    }

    /**
     * Review counts per day since {@code since}, oldest day first; days without reviews are left
     * out. A {@code deckId} of 0 or less counts every deck.
     */
    public List<DailyCount> dailyCounts(long deckId, LocalDateTime since) throws SQLException {
        String sql = deckId <= 0 ? """
            SELECT substr(l.reviewed_at, 1, 10) AS day,
                   COUNT(*) AS reviews,
                   SUM(CASE WHEN %s THEN 1 ELSE 0 END) AS correct
            FROM review_logs l
            WHERE l.reviewed_at >= ?
            GROUP BY day
            ORDER BY day
            """.formatted(CORRECT) : """
            SELECT substr(l.reviewed_at, 1, 10) AS day,
                   COUNT(*) AS reviews,
                   SUM(CASE WHEN %s THEN 1 ELSE 0 END) AS correct
            FROM review_logs l
            JOIN words w ON w.id = l.word_id
            WHERE w.deck_id = ? AND l.reviewed_at >= ?
            GROUP BY day
            ORDER BY day
            """.formatted(CORRECT);
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
     * The deck's active words with the lowest average answer similarity, then the most reviews that
     * were not correct (that counted as Again) and the most reviews. Words never reviewed are left out.
     */
    public List<HardWordStat> hardestWords(long deckId, int limit) throws SQLException {
        String sql = """
            SELECT w.english, w.chinese, COUNT(l.id) AS reviews,
                   AVG(l.similarity) AS avg_similarity,
                   SUM(CASE WHEN %s THEN 0 ELSE 1 END) AS again_count
            FROM words w
            JOIN review_logs l ON l.word_id = w.id
            WHERE w.deck_id = ? AND w.archived = 0
            GROUP BY w.id
            ORDER BY avg_similarity ASC, again_count DESC, reviews DESC
            LIMIT ?
            """.formatted(CORRECT);
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

    /** Correct reviews ({@link ReviewLog#isCorrect()}) since {@code since}, in every deck. */
    public int countCorrectSince(LocalDateTime since) throws SQLException {
        return scalarInt("SELECT COUNT(*) FROM review_logs l WHERE l.reviewed_at >= ? AND " + CORRECT, since);
    }

    public int countSince(long deckId, LocalDateTime since) throws SQLException {
        return scalarInt("""
            SELECT COUNT(*)
            FROM review_logs l
            JOIN words w ON w.id = l.word_id
            WHERE w.deck_id = ? AND l.reviewed_at >= ?
            """, deckId, since);
    }

    /** Correct reviews ({@link ReviewLog#isCorrect()}) of the deck since {@code since}. */
    public int countCorrectSince(long deckId, LocalDateTime since) throws SQLException {
        return scalarInt("""
            SELECT COUNT(*)
            FROM review_logs l
            JOIN words w ON w.id = l.word_id
            WHERE w.deck_id = ? AND l.reviewed_at >= ? AND %s
            """.formatted(CORRECT), deckId, since);
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
