package com.vocabtrainer.repository;

import com.vocabtrainer.util.DateTimeUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
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
