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
        String sql = """
            INSERT OR IGNORE INTO achievements(code, name, description, unlocked_at, xp_reward)
            VALUES(?, ?, ?, ?, ?)
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, achievement.code());
            statement.setString(2, achievement.name());
            statement.setString(3, achievement.description());
            statement.setString(4, DateTimeUtil.toDatabase(achievement.unlockedAt()));
            statement.setInt(5, achievement.xpReward());
            return statement.executeUpdate() > 0;
        }
    }

    public boolean exists(String code) throws SQLException {
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT 1 FROM achievements WHERE code = ?")) {
            statement.setString(1, code);
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
