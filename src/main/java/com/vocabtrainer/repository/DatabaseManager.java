package com.vocabtrainer.repository;

import com.vocabtrainer.util.DateTimeUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseManager {
    private final Path databasePath;
    private final String jdbcUrl;

    public DatabaseManager() {
        this(DateTimeUtil.defaultDatabasePath());
    }

    public DatabaseManager(Path databasePath) {
        this.databasePath = databasePath;
        this.jdbcUrl = "jdbc:sqlite:" + databasePath.toAbsolutePath();
    }

    public void initialize() throws SQLException {
        try {
            Path parent = databasePath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new SQLException("无法创建数据库目录: " + databasePath, e);
        }

        try (Connection connection = getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS decks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL UNIQUE,
                    created_at TEXT NOT NULL
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS words (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    deck_id INTEGER NOT NULL,
                    english TEXT NOT NULL,
                    chinese TEXT NOT NULL,
                    phonetic TEXT,
                    part_of_speech TEXT,
                    example_sentence TEXT,
                    note TEXT,
                    tags TEXT,
                    added_at TEXT NOT NULL,
                    last_reviewed_at TEXT,
                    next_review_at TEXT NOT NULL,
                    easiness_factor REAL NOT NULL,
                    interval_days INTEGER NOT NULL,
                    repetitions INTEGER NOT NULL,
                    consecutive_correct INTEGER NOT NULL,
                    lapses INTEGER NOT NULL,
                    archived INTEGER NOT NULL DEFAULT 0,
                    FOREIGN KEY(deck_id) REFERENCES decks(id) ON DELETE CASCADE
                )
                """);
            statement.executeUpdate("""
                CREATE UNIQUE INDEX IF NOT EXISTS idx_words_deck_english
                ON words(deck_id, english COLLATE NOCASE)
                """);
            statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_words_due
                ON words(deck_id, archived, next_review_at)
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS review_logs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    word_id INTEGER NOT NULL,
                    reviewed_at TEXT NOT NULL,
                    user_answer TEXT,
                    correct_answer TEXT NOT NULL,
                    similarity REAL NOT NULL,
                    rating TEXT NOT NULL,
                    elapsed_millis INTEGER NOT NULL,
                    FOREIGN KEY(word_id) REFERENCES words(id) ON DELETE CASCADE
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS settings (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS ai_cache (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    cache_key TEXT NOT NULL UNIQUE,
                    response TEXT NOT NULL,
                    created_at TEXT NOT NULL
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS daily_goals (
                    goal_date TEXT PRIMARY KEY,
                    review_goal INTEGER NOT NULL,
                    new_word_goal INTEGER NOT NULL,
                    session_goal INTEGER NOT NULL,
                    reviewed_count INTEGER NOT NULL DEFAULT 0,
                    correct_count INTEGER NOT NULL DEFAULT 0,
                    new_words_count INTEGER NOT NULL DEFAULT 0,
                    xp_earned INTEGER NOT NULL DEFAULT 0,
                    completed INTEGER NOT NULL DEFAULT 0
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS achievements (
                    code TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    description TEXT NOT NULL,
                    unlocked_at TEXT NOT NULL,
                    xp_reward INTEGER NOT NULL
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS dictionary_cache (
                    english TEXT PRIMARY KEY COLLATE NOCASE,
                    payload TEXT NOT NULL,
                    source TEXT NOT NULL,
                    created_at TEXT NOT NULL
                )
                """);
        }
    }

    public Connection getConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
        }
        return connection;
    }

    public Path getDatabasePath() {
        return databasePath;
    }
}
