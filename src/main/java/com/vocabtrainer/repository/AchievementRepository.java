package com.vocabtrainer.repository;

import com.vocabtrainer.domain.Achievement;
import com.vocabtrainer.util.DateTimeUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class AchievementRepository {
    private final DatabaseManager databaseManager;

    public AchievementRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public boolean insertIfAbsent(Achievement achievement) throws SQLException {
        return insertIfAbsent(0L, achievement);
    }

    public boolean insertIfAbsent(long deckId, Achievement achievement) throws SQLException {
        String sql = """
            INSERT OR IGNORE INTO achievements(deck_id, code, name, description, unlocked_at, xp_reward)
            VALUES(?, ?, ?, ?, ?, ?)
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, deckId);
            statement.setString(2, achievement.code());
            statement.setString(3, achievement.name());
            statement.setString(4, achievement.description());
            statement.setString(5, DateTimeUtil.toDatabase(achievement.unlockedAt()));
            statement.setInt(6, achievement.xpReward());
            return statement.executeUpdate() > 0;
        }
    }

    public boolean exists(String code) throws SQLException {
        return exists(0L, code);
    }

    public boolean exists(long deckId, String code) throws SQLException {
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT 1 FROM achievements WHERE deck_id = ? AND code = ?")) {
            statement.setLong(1, deckId);
            statement.setString(2, code);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    public List<Achievement> findAll() throws SQLException {
        String sql = "SELECT * FROM achievements ORDER BY unlocked_at ASC";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            List<Achievement> achievements = new ArrayList<>();
            while (rs.next()) {
                achievements.add(map(rs));
            }
            return achievements;
        }
    }

    public List<Achievement> findAll(long deckId) throws SQLException {
        String sql = "SELECT * FROM achievements WHERE deck_id = ? ORDER BY unlocked_at ASC";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, deckId);
            try (ResultSet rs = statement.executeQuery()) {
                List<Achievement> achievements = new ArrayList<>();
                while (rs.next()) {
                    achievements.add(map(rs));
                }
                return achievements;
            }
        }
    }

    private Achievement map(ResultSet rs) throws SQLException {
        return new Achievement(
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("description"),
            DateTimeUtil.fromDatabase(rs.getString("unlocked_at")),
            rs.getInt("xp_reward")
        );
    }
}
