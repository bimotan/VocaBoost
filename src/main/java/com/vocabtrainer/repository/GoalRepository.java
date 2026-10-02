package com.vocabtrainer.repository;

import com.vocabtrainer.util.DateTimeUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class GoalRepository {
    private final DatabaseManager databaseManager;

    public GoalRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public record GoalRow(
        long deckId,
        LocalDate date,
        int reviewGoal,
        int newWordGoal,
        int sessionGoal,
        int reviewedCount,
        int correctCount,
        int newWordsCount,
        int xpEarned,
        boolean completed
    ) {
    }

    public GoalRow ensure(LocalDate date, int reviewGoal, int newWordGoal, int sessionGoal) throws SQLException {
        return ensure(0L, date, reviewGoal, newWordGoal, sessionGoal);
    }

    public GoalRow ensure(long deckId, LocalDate date, int reviewGoal, int newWordGoal, int sessionGoal) throws SQLException {
        String sql = """
            INSERT OR IGNORE INTO daily_goals(deck_id, goal_date, review_goal, new_word_goal, session_goal)
            VALUES(?, ?, ?, ?, ?)
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, deckId);
            statement.setString(2, DateTimeUtil.toDatabaseDate(date));
            statement.setInt(3, reviewGoal);
            statement.setInt(4, newWordGoal);
            statement.setInt(5, sessionGoal);
            statement.executeUpdate();
        }
        return find(deckId, date).orElseThrow(() -> new SQLException("Daily goal was not created"));
    }

    public Optional<GoalRow> find(LocalDate date) throws SQLException {
        return find(0L, date);
    }

    public Optional<GoalRow> find(long deckId, LocalDate date) throws SQLException {
        String sql = "SELECT * FROM daily_goals WHERE deck_id = ? AND goal_date = ?";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, deckId);
            statement.setString(2, DateTimeUtil.toDatabaseDate(date));
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(map(rs));
                }
            }
        }
        return Optional.empty();
    }

    /** The deck's goal rows, oldest day first. */
    public List<GoalRow> findAll(long deckId) throws SQLException {
        String sql = "SELECT * FROM daily_goals WHERE deck_id = ? ORDER BY goal_date";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, deckId);
            try (ResultSet rs = statement.executeQuery()) {
                List<GoalRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(map(rs));
                }
                return rows;
            }
        }
    }

    /**
     * Writes a day of goal history from a backup unless the deck already has progress for that day,
     * so restoring the same history twice never counts it twice. A row with no progress yet (older
     * versions created one whenever the dashboard was shown) is replaced, but only by a row that has
     * progress, so restoring an empty day again changes nothing. Returns whether the row was written.
     */
    public boolean restoreRow(GoalRow row) throws SQLException {
        String sql = """
            INSERT INTO daily_goals(deck_id, goal_date, review_goal, new_word_goal, session_goal,
                reviewed_count, correct_count, new_words_count, xp_earned, completed)
            VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(deck_id, goal_date) DO UPDATE SET
                review_goal = excluded.review_goal,
                new_word_goal = excluded.new_word_goal,
                session_goal = excluded.session_goal,
                reviewed_count = excluded.reviewed_count,
                correct_count = excluded.correct_count,
                new_words_count = excluded.new_words_count,
                xp_earned = excluded.xp_earned,
                completed = excluded.completed
            WHERE daily_goals.reviewed_count = 0 AND daily_goals.correct_count = 0
              AND daily_goals.new_words_count = 0 AND daily_goals.xp_earned = 0 AND daily_goals.completed = 0
              AND (excluded.reviewed_count > 0 OR excluded.correct_count > 0 OR excluded.new_words_count > 0
                   OR excluded.xp_earned > 0 OR excluded.completed <> 0)
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, row.deckId());
            statement.setString(2, DateTimeUtil.toDatabaseDate(row.date()));
            statement.setInt(3, row.reviewGoal());
            statement.setInt(4, row.newWordGoal());
            statement.setInt(5, row.sessionGoal());
            statement.setInt(6, row.reviewedCount());
            statement.setInt(7, row.correctCount());
            statement.setInt(8, row.newWordsCount());
            statement.setInt(9, row.xpEarned());
            statement.setInt(10, row.completed() ? 1 : 0);
            return statement.executeUpdate() > 0;
        }
    }

    /**
     * Adds to the deck's row of {@code date} and returns the row afterwards. A day without a row gets
     * one; either way the row is given the goals passed in, so it keeps the goals that were in effect
     * at the day's last review. The counters are only kept for older versions and backups: the app
     * reads the day's reviews and new words from the review logs.
     */
    public GoalRow recordProgress(long deckId, LocalDate date, int reviewGoal, int newWordGoal, int sessionGoal,
                                  int reviewDelta, int correctDelta, int newWordDelta, int xpDelta) throws SQLException {
        String sql = """
            INSERT INTO daily_goals(deck_id, goal_date, review_goal, new_word_goal, session_goal,
                reviewed_count, correct_count, new_words_count, xp_earned)
            VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(deck_id, goal_date) DO UPDATE SET
                review_goal = excluded.review_goal,
                new_word_goal = excluded.new_word_goal,
                session_goal = excluded.session_goal,
                reviewed_count = daily_goals.reviewed_count + excluded.reviewed_count,
                correct_count = daily_goals.correct_count + excluded.correct_count,
                new_words_count = daily_goals.new_words_count + excluded.new_words_count,
                xp_earned = daily_goals.xp_earned + excluded.xp_earned
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, deckId);
            statement.setString(2, DateTimeUtil.toDatabaseDate(date));
            statement.setInt(3, reviewGoal);
            statement.setInt(4, newWordGoal);
            statement.setInt(5, sessionGoal);
            statement.setInt(6, reviewDelta);
            statement.setInt(7, correctDelta);
            statement.setInt(8, newWordDelta);
            statement.setInt(9, xpDelta);
            statement.executeUpdate();
        }
        return find(deckId, date).orElseThrow(() -> new SQLException("Daily goal was not written: " + date));
    }

    public GoalRow addProgress(LocalDate date, int reviewDelta, int correctDelta,
                               int newWordDelta, int xpDelta) throws SQLException {
        return addProgress(0L, date, reviewDelta, correctDelta, newWordDelta, xpDelta);
    }

    public GoalRow addProgress(long deckId, LocalDate date, int reviewDelta, int correctDelta,
                               int newWordDelta, int xpDelta) throws SQLException {
        String sql = """
            UPDATE daily_goals
            SET reviewed_count = reviewed_count + ?,
                correct_count = correct_count + ?,
                new_words_count = new_words_count + ?,
                xp_earned = xp_earned + ?
            WHERE deck_id = ? AND goal_date = ?
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, reviewDelta);
            statement.setInt(2, correctDelta);
            statement.setInt(3, newWordDelta);
            statement.setInt(4, xpDelta);
            statement.setLong(5, deckId);
            statement.setString(6, DateTimeUtil.toDatabaseDate(date));
            statement.executeUpdate();
        }
        return find(deckId, date).orElseThrow(() -> new SQLException("Daily goal not found: " + date));
    }

    public void markCompleted(LocalDate date) throws SQLException {
        markCompleted(0L, date);
    }

    public void markCompleted(long deckId, LocalDate date) throws SQLException {
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "UPDATE daily_goals SET completed = 1 WHERE deck_id = ? AND goal_date = ?")) {
            statement.setLong(1, deckId);
            statement.setString(2, DateTimeUtil.toDatabaseDate(date));
            statement.executeUpdate();
        }
    }

    /** Daily goal rows in every deck; any row means the app has been used with this database before. */
    public int countAll() throws SQLException {
        return scalarInt("SELECT COUNT(*) FROM daily_goals", null);
    }

    public int totalXp() throws SQLException {
        return scalarInt("SELECT COALESCE(SUM(xp_earned), 0) FROM daily_goals", null);
    }

    public int totalXp(long deckId) throws SQLException {
        return scalarInt("SELECT COALESCE(SUM(xp_earned), 0) FROM daily_goals WHERE deck_id = ?", deckId, null);
    }

    private int scalarInt(String sql, LocalDate date) throws SQLException {
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (date != null) {
                statement.setString(1, DateTimeUtil.toDatabaseDate(date));
            }
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private int scalarInt(String sql, long deckId, LocalDate date) throws SQLException {
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, deckId);
            if (date != null) {
                statement.setString(2, DateTimeUtil.toDatabaseDate(date));
            }
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private GoalRow map(ResultSet rs) throws SQLException {
        return new GoalRow(
            rs.getLong("deck_id"),
            DateTimeUtil.dateFromDatabase(rs.getString("goal_date")),
            rs.getInt("review_goal"),
            rs.getInt("new_word_goal"),
            rs.getInt("session_goal"),
            rs.getInt("reviewed_count"),
            rs.getInt("correct_count"),
            rs.getInt("new_words_count"),
            rs.getInt("xp_earned"),
            rs.getInt("completed") == 1
        );
    }
}
