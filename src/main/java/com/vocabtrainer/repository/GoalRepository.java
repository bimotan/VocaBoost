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
            statement.setString(2, date.toString());
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
            statement.setString(2, date.toString());
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
     * so restoring the same history twice never counts it twice. A row with no progress yet (the
     * placeholder the dashboard creates for today) is replaced, but only by a row that has progress,
     * so restoring an empty day again changes nothing. Returns whether the row was written.
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
            statement.setString(2, row.date().toString());
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
            statement.setString(6, date.toString());
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
            statement.setString(2, date.toString());
            statement.executeUpdate();
        }
    }

    public boolean hasReviewedOn(LocalDate date) throws SQLException {
        return hasReviewedOn(0L, date);
    }

    public boolean hasReviewedOn(long deckId, LocalDate date) throws SQLException {
        return scalarInt("SELECT reviewed_count FROM daily_goals WHERE deck_id = ? AND goal_date = ?", deckId, date) > 0;
    }

    /** Daily goal rows in every deck; any row means the app has been used with this database before. */
    public int countAll() throws SQLException {
        return scalarInt("SELECT COUNT(*) FROM daily_goals", null);
    }

    public int totalReviews() throws SQLException {
        return scalarInt("SELECT COALESCE(SUM(reviewed_count), 0) FROM daily_goals", null);
    }

    public int totalReviews(long deckId) throws SQLException {
        return scalarInt("SELECT COALESCE(SUM(reviewed_count), 0) FROM daily_goals WHERE deck_id = ?", deckId, null);
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
                statement.setString(1, date.toString());
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
                statement.setString(2, date.toString());
            }
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private GoalRow map(ResultSet rs) throws SQLException {
        return new GoalRow(
            rs.getLong("deck_id"),
            LocalDate.parse(rs.getString("goal_date")),
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
