package com.vocabtrainer.repository;

import com.vocabtrainer.domain.Achievement;
import com.vocabtrainer.util.DateTimeUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AchievementRepository {
    /**
     * The deck id of achievements that belong to no deck, such as the streak badges, which count the
     * reviews of every deck. No deck has this id.
     */
    public static final long NO_DECK = 0L;

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

    /**
     * Deletes the deck's achievement with this code ({@link #NO_DECK} for a streak badge), such as
     * one an undone review unlocked; returns whether it existed.
     */
    public boolean delete(long deckId, String code) throws SQLException {
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "DELETE FROM achievements WHERE deck_id = ? AND code = ?")) {
            statement.setLong(1, deckId);
            statement.setString(2, code);
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

    /** Whether the achievement was unlocked in any deck or for no deck. */
    public boolean existsInAnyDeck(String code) throws SQLException {
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM achievements WHERE code = ? LIMIT 1")) {
            statement.setString(1, code);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * What the deck shows as unlocked: its own achievements, those of {@link #NO_DECK}, and the
     * achievements with one of {@code sharedCodes} unlocked in any deck (older versions unlocked
     * streak badges per deck). Each code once, the first unlock, oldest first.
     */
    public List<Achievement> findShown(long deckId, Collection<String> sharedCodes) throws SQLException {
        String placeholders = String.join(", ", Collections.nCopies(sharedCodes.size(), "?"));
        String sql = "SELECT * FROM achievements WHERE deck_id = ? OR deck_id = ?"
            + (sharedCodes.isEmpty() ? "" : " OR code IN (" + placeholders + ")")
            + " ORDER BY unlocked_at ASC, deck_id ASC";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setLong(index++, deckId);
            statement.setLong(index++, NO_DECK);
            for (String code : sharedCodes) {
                statement.setString(index++, code);
            }
            try (ResultSet rs = statement.executeQuery()) {
                Map<String, Achievement> byCode = new LinkedHashMap<>();
                while (rs.next()) {
                    Achievement achievement = map(rs);
                    byCode.putIfAbsent(achievement.code(), achievement);
                }
                return new ArrayList<>(byCode.values());
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
