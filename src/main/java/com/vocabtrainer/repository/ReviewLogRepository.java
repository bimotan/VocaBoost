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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
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
    /**
     * A review_logs row aliased {@code l} is a review: not the practice of a word that was not due
     * ({@link ReviewKind#PRACTICE}), which counts towards no goal, accuracy or streak, and not a new
     * word marked as already known ({@link ReviewKind#KNOWN}), which was no review at all. A kind
     * this version does not know reads as a review, as {@link ReviewLog#getKind()} does.
     */
    static final String IS_REVIEW = "l.kind NOT IN ('PRACTICE', 'KNOWN')";
    /** A review_logs row aliased {@code l} records an answer: anything but {@link ReviewKind#KNOWN}. */
    static final String IS_ANSWER = "l.kind <> 'KNOWN'";

    /** At most this many ids are bound in one {@code IN} list. */
    private static final int IDS_PER_STATEMENT = 500;

    private final DatabaseManager databaseManager;

    /**
     * The reviews of one study day: how many there were, how many were correct
     * ({@link ReviewLog#isCorrect()}) and how many were the first review of a new word
     * ({@link ReviewKind#LEARN}). Practice is not counted.
     */
    public record DailyCount(LocalDate day, int reviews, int correct, int newWords) {
        public static DailyCount none(LocalDate day) {
            return new DailyCount(day, 0, 0, 0);
        }

        /** Correct reviews over all reviews; 0 on a day without reviews. */
        public double accuracy() {
            return reviews == 0 ? 0.0 : correct / (double) reviews;
        }
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

    /** Deletes one log, such as the log of a rating that is being undone; returns whether it existed. */
    public boolean deleteById(long id) throws SQLException {
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM review_logs WHERE id = ?")) {
            statement.setLong(1, id);
            return statement.executeUpdate() > 0;
        }
    }

    /** How many logs the words have together: what deleting them would delete too. */
    public int countByWords(Collection<Long> wordIds) throws SQLException {
        List<Long> ids = List.copyOf(new LinkedHashSet<>(wordIds));
        int count = 0;
        try (Connection connection = databaseManager.getConnection()) {
            for (int from = 0; from < ids.size(); from += IDS_PER_STATEMENT) {
                List<Long> chunk = ids.subList(from, Math.min(ids.size(), from + IDS_PER_STATEMENT));
                String sql = "SELECT COUNT(*) FROM review_logs WHERE word_id IN ("
                    + String.join(", ", Collections.nCopies(chunk.size(), "?")) + ")";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    for (int index = 0; index < chunk.size(); index++) {
                        statement.setLong(index + 1, chunk.get(index));
                    }
                    try (ResultSet rs = statement.executeQuery()) {
                        count += rs.next() ? rs.getInt(1) : 0;
                    }
                }
            }
        }
        return count;
    }

    /** Every review log of the deck's words, suspended words included, oldest first. */
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

    /** The stored direction, or null when unknown (or one this version does not know). */
    private static ReviewMode direction(String value) {
        if (value == null) {
            return null;
        }
        try {
            ReviewMode direction = ReviewMode.valueOf(value);
            return direction.isDirection() ? direction : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
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
     * The reviews of each study day from {@code from} (inclusive) to {@code to} (exclusive), oldest
     * day first; days without reviews are left out. This is where every daily number of the app
     * comes from: the dashboard, the daily goal, the statistics and the report.
     *
     * @param deckId       the deck whose reviews count; 0 or less counts every deck
     * @param rolloverHour the hour (0 to 23) at which a study day starts, so a review at 1 am counts
     *                     for the day before when it is 4 (see {@code StudyDay})
     */
    public List<DailyCount> dailyCounts(long deckId, LocalDateTime from, LocalDateTime to, int rolloverHour)
        throws SQLException {
        if (rolloverHour < 0 || rolloverHour > 23) {
            throw new IllegalArgumentException("The day rollover hour must be from 0 to 23: " + rolloverHour);
        }
        String deckJoin = deckId > 0 ? "JOIN words w ON w.id = l.word_id" : "";
        String deckFilter = deckId > 0 ? "w.deck_id = ? AND" : "";
        String sql = """
            SELECT date(l.reviewed_at, ?) AS day,
                   COUNT(*) AS reviews,
                   SUM(CASE WHEN %s THEN 1 ELSE 0 END) AS correct,
                   SUM(CASE WHEN l.kind = 'LEARN' THEN 1 ELSE 0 END) AS new_words
            FROM review_logs l
            %s
            WHERE %s l.reviewed_at >= ? AND l.reviewed_at < ? AND %s
            GROUP BY day
            ORDER BY day
            """.formatted(CORRECT, deckJoin, deckFilter, IS_REVIEW);
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            // SQLite's date() moves the time back by the rollover hour, so the date is the study day.
            statement.setString(index++, "-" + rolloverHour + " hours");
            if (deckId > 0) {
                statement.setLong(index++, deckId);
            }
            statement.setString(index++, DateTimeUtil.toDatabase(from));
            statement.setString(index, DateTimeUtil.toDatabase(to));
            try (ResultSet rs = statement.executeQuery()) {
                List<DailyCount> counts = new ArrayList<>();
                while (rs.next()) {
                    counts.add(new DailyCount(DateTimeUtil.dateFromDatabase(rs.getString("day")), rs.getInt("reviews"),
                        rs.getInt("correct"), rs.getInt("new_words")));
                }
                return counts;
            }
        }
    }

    /**
     * The study days with a review in any deck (practice and Already known do not count), stepping
     * back from {@code before}: the day of the newest review before it, then the day of the newest
     * review before that day started, and so on while each day is the one before the last. The last
     * day listed is the first one that breaks the run (it is not the day before the previous one), so
     * the caller sees where the run ended; empty when there is no review before {@code before}.
     *
     * <p>One query, whatever the length of the run: a recursive query takes one indexed lookup per
     * day inside SQLite, which is how the streak is counted (see {@code GoalService}).
     *
     * @param rolloverHour the hour (0 to 23) at which a study day starts, as in {@link #dailyCounts}
     */
    public List<LocalDate> reviewDaysBackFrom(LocalDateTime before, int rolloverHour) throws SQLException {
        if (rolloverHour < 0 || rolloverHour > 23) {
            throw new IllegalArgumentException("The day rollover hour must be from 0 to 23: " + rolloverHour);
        }
        // date(t, '-H hours') is the study day of t, and strftime(..., day, '+H hours') when that day
        // starts, written as DateTimeUtil.toDatabase writes times, so the two compare as text.
        String newestBefore = """
            (SELECT l.reviewed_at FROM review_logs l
             WHERE l.reviewed_at < %s AND %s
             ORDER BY l.reviewed_at DESC
             LIMIT 1)
            """;
        String sql = """
            WITH RECURSIVE run(day, later) AS (
                SELECT date(%s, :shift), NULL
                UNION ALL
                SELECT date(%s, :shift), run.day
                FROM run
                WHERE run.day IS NOT NULL AND (run.later IS NULL OR run.day = date(run.later, '-1 day'))
            )
            SELECT day FROM run WHERE day IS NOT NULL
            """.formatted(
                newestBefore.formatted("?", IS_REVIEW),
                newestBefore.formatted("strftime('%Y-%m-%dT%H:%M:%S', run.day, :start)", IS_REVIEW))
            .replace(":shift", "'-" + rolloverHour + " hours'")
            .replace(":start", "'+" + rolloverHour + " hours'");
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, DateTimeUtil.toDatabase(before));
            try (ResultSet rs = statement.executeQuery()) {
                List<LocalDate> days = new ArrayList<>();
                while (rs.next()) {
                    days.add(DateTimeUtil.dateFromDatabase(rs.getString(1)));
                }
                return days;
            }
        }
    }

    /**
     * How many reviews the deck's words had, suspended words included and practice not; counting
     * stops at {@code atMost}, so asking whether there were at least 100 reads at most 100 logs.
     */
    public int countReviews(long deckId, int atMost) throws SQLException {
        String sql = """
            SELECT COUNT(*) FROM (
                SELECT 1
                FROM review_logs l
                JOIN words w ON w.id = l.word_id
                WHERE w.deck_id = ? AND %s
                LIMIT ?
            )
            """.formatted(IS_REVIEW);
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, deckId);
            statement.setInt(2, Math.max(0, atMost));
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * The deck's words in study (not suspended) with the lowest average answer similarity, then the
     * most reviews that were not correct (that counted as Again) and the most reviews. Words never
     * answered are left out; marking a word as already known is no answer.
     */
    public List<HardWordStat> hardestWords(long deckId, int limit) throws SQLException {
        String sql = """
            SELECT w.english, w.chinese, COUNT(l.id) AS reviews,
                   AVG(l.similarity) AS avg_similarity,
                   SUM(CASE WHEN %s THEN 0 ELSE 1 END) AS again_count
            FROM words w
            JOIN review_logs l ON l.word_id = w.id
            WHERE w.deck_id = ? AND w.archived = 0 AND %s
            GROUP BY w.id
            ORDER BY avg_similarity ASC, again_count DESC, reviews DESC
            LIMIT ?
            """.formatted(CORRECT, IS_ANSWER);
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

    /**
     * When a word of the deck, suspended words included, was last reviewed or practiced; marking a
     * word as already known is not a review.
     */
    public Optional<LocalDateTime> latestReviewAt(long deckId) throws SQLException {
        String sql = """
            SELECT MAX(l.reviewed_at)
            FROM review_logs l
            JOIN words w ON w.id = l.word_id
            WHERE w.deck_id = ? AND %s
            """.formatted(IS_ANSWER);
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

    public int countByRating(ReviewRating rating) throws SQLException {
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM review_logs WHERE rating = ?")) {
            statement.setString(1, rating.name());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** {@link #latestReviewAt} of every deck that has reviews, by deck id, in one query. */
    public Map<Long, LocalDateTime> latestReviewByDeck() throws SQLException {
        String sql = """
            SELECT w.deck_id, MAX(l.reviewed_at) AS latest
            FROM review_logs l
            JOIN words w ON w.id = l.word_id
            WHERE %s
            GROUP BY w.deck_id
            """.formatted(IS_ANSWER);
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
