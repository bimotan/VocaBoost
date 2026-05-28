package com.vocabtrainer.repository;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.util.DateTimeUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Optional;

public class DeckRepository {
    public static final String DEFAULT_DECK_NAME = "默认词库";

    private final DatabaseManager databaseManager;

    public DeckRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public Deck ensureDefaultDeck() throws SQLException {
        Optional<Deck> existing = findByName(DEFAULT_DECK_NAME);
        if (existing.isPresent()) {
            return existing.get();
        }
        return create(DEFAULT_DECK_NAME);
    }

    public Deck create(String name) throws SQLException {
        LocalDateTime now = LocalDateTime.now();
        String sql = "INSERT INTO decks(name, created_at) VALUES(?, ?)";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, name.trim());
            statement.setString(2, DateTimeUtil.toDatabase(now));
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return new Deck(keys.getLong(1), name.trim(), now);
                }
            }
        }
        throw new SQLException("创建词库失败");
    }

    public Optional<Deck> findByName(String name) throws SQLException {
        String sql = "SELECT id, name, created_at FROM decks WHERE name = ?";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(map(rs));
                }
            }
        }
        return Optional.empty();
    }

    public Optional<Deck> findById(long id) throws SQLException {
        String sql = "SELECT id, name, created_at FROM decks WHERE id = ?";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(map(rs));
                }
            }
        }
        return Optional.empty();
    }

    private Deck map(ResultSet rs) throws SQLException {
        return new Deck(
            rs.getLong("id"),
            rs.getString("name"),
            DateTimeUtil.fromDatabase(rs.getString("created_at"))
        );
    }
}