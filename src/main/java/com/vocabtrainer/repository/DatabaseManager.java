package com.vocabtrainer.repository;

import com.vocabtrainer.util.DateTimeUtil;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DatabaseManager implements TransactionRunner {
    private static final Logger LOGGER = Logger.getLogger(DatabaseManager.class.getName());
    /**
     * The note FallbackAiService appends to the mock text when the AI provider fails. Versions
     * that cached the fallback's output stored it in ai_cache; the text must stay exactly what
     * those versions wrote.
     */
    private static final String CACHED_AI_FALLBACK_NOTE = "AI provider failed; mock fallback was used.";

    private final Path databasePath;
    private final String jdbcUrl;
    private final ThreadLocal<Transaction> activeTransaction = new ThreadLocal<>();

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
                    created_at TEXT NOT NULL,
                    archived INTEGER NOT NULL DEFAULT 0
                )
                """);
            addColumnIfMissing(statement, "decks", "archived", "INTEGER NOT NULL DEFAULT 0");
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
                    deck_id INTEGER NOT NULL DEFAULT 0,
                    goal_date TEXT NOT NULL,
                    review_goal INTEGER NOT NULL,
                    new_word_goal INTEGER NOT NULL,
                    session_goal INTEGER NOT NULL,
                    reviewed_count INTEGER NOT NULL DEFAULT 0,
                    correct_count INTEGER NOT NULL DEFAULT 0,
                    new_words_count INTEGER NOT NULL DEFAULT 0,
                    xp_earned INTEGER NOT NULL DEFAULT 0,
                    completed INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(deck_id, goal_date)
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS achievements (
                    deck_id INTEGER NOT NULL DEFAULT 0,
                    code TEXT NOT NULL,
                    name TEXT NOT NULL,
                    description TEXT NOT NULL,
                    unlocked_at TEXT NOT NULL,
                    xp_reward INTEGER NOT NULL,
                    PRIMARY KEY(deck_id, code)
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
        try (Connection connection = getConnection()) {
            migrateDailyGoalsToDeckScope(connection);
            migrateAchievementsToDeckScope(connection);
            purgeCachedAiFallbackText(connection);
        }
    }

    private void addColumnIfMissing(Statement statement, String table, String column, String definition) throws SQLException {
        try {
            statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        } catch (SQLException e) {
            String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (!message.contains("duplicate column name")) {
                throw e;
            }
        }
    }

    private void migrateDailyGoalsToDeckScope(Connection connection) throws SQLException {
        if (hasColumn(connection, "daily_goals", "deck_id")) {
            return;
        }
        long deckId = firstDeckId(connection);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE daily_goals RENAME TO daily_goals_old");
            statement.executeUpdate("""
                CREATE TABLE daily_goals (
                    deck_id INTEGER NOT NULL DEFAULT 0,
                    goal_date TEXT NOT NULL,
                    review_goal INTEGER NOT NULL,
                    new_word_goal INTEGER NOT NULL,
                    session_goal INTEGER NOT NULL,
                    reviewed_count INTEGER NOT NULL DEFAULT 0,
                    correct_count INTEGER NOT NULL DEFAULT 0,
                    new_words_count INTEGER NOT NULL DEFAULT 0,
                    xp_earned INTEGER NOT NULL DEFAULT 0,
                    completed INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(deck_id, goal_date)
                )
                """);
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT OR IGNORE INTO daily_goals(deck_id, goal_date, review_goal, new_word_goal, session_goal,
                reviewed_count, correct_count, new_words_count, xp_earned, completed)
            SELECT ?, goal_date, review_goal, new_word_goal, session_goal,
                reviewed_count, correct_count, new_words_count, xp_earned, completed
            FROM daily_goals_old
            """)) {
            statement.setLong(1, deckId);
            statement.executeUpdate();
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE daily_goals_old");
        }
    }

    private void migrateAchievementsToDeckScope(Connection connection) throws SQLException {
        if (hasColumn(connection, "achievements", "deck_id")) {
            return;
        }
        long deckId = firstDeckId(connection);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE achievements RENAME TO achievements_old");
            statement.executeUpdate("""
                CREATE TABLE achievements (
                    deck_id INTEGER NOT NULL DEFAULT 0,
                    code TEXT NOT NULL,
                    name TEXT NOT NULL,
                    description TEXT NOT NULL,
                    unlocked_at TEXT NOT NULL,
                    xp_reward INTEGER NOT NULL,
                    PRIMARY KEY(deck_id, code)
                )
                """);
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT OR IGNORE INTO achievements(deck_id, code, name, description, unlocked_at, xp_reward)
            SELECT ?, code, name, description, unlocked_at, xp_reward
            FROM achievements_old
            """)) {
            statement.setLong(1, deckId);
            statement.executeUpdate();
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE achievements_old");
        }
    }

    /**
     * Deletes AI explanations that are really the mock fallback text. Older versions cached it
     * after any provider error, so that word never got a real explanation. The fallback is no
     * longer cached, so after the first run this matches nothing.
     */
    private void purgeCachedAiFallbackText(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM ai_cache WHERE instr(response, ?) > 0")) {
            statement.setString(1, CACHED_AI_FALLBACK_NOTE);
            int deleted = statement.executeUpdate();
            if (deleted > 0) {
                LOGGER.info("Deleted " + deleted + " cached AI fallback explanation(s) so they are requested again");
            }
        }
    }

    private boolean hasColumn(Connection connection, String table, String column) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("PRAGMA table_info(" + table + ")");
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private long firstDeckId(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT id FROM decks ORDER BY id LIMIT 1")) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    /**
     * Returns a new connection, or, while this thread is inside {@link #inTransaction}, the
     * transaction's connection. Closing that one is a no-op, so repositories can keep using
     * try-with-resources.
     */
    public Connection getConnection() throws SQLException {
        Transaction transaction = activeTransaction.get();
        if (transaction != null) {
            return transaction.participant;
        }
        return openConnection();
    }

    /**
     * Runs {@code work} in one transaction bound to the current thread. Every
     * {@link #getConnection()} call the work makes on this thread joins it, and a nested
     * {@code inTransaction} call joins the outer one. The outermost call commits when the work
     * returns and rolls back if it throws anything; a failed nested call makes the whole
     * transaction roll back even if the outer work catches the exception.
     */
    @Override
    public <T> T inTransaction(SqlWork<T> work) throws SQLException {
        Transaction current = activeTransaction.get();
        if (current != null) {
            try {
                return work.run();
            } catch (Throwable e) {
                current.rollbackOnly = true;
                throw e;
            }
        }

        Connection connection = openConnection();
        Transaction transaction = new Transaction(connection);
        activeTransaction.set(transaction);
        boolean started = false;
        try {
            // BEGIN IMMEDIATE takes the write lock up front: if another connection is writing, this
            // waits for the busy timeout instead of failing at once when a read is later upgraded
            // to a write. Plain SQL is used instead of setAutoCommit(false)/commit() because the
            // driver's commit() immediately opens the next transaction.
            execute(connection, "BEGIN IMMEDIATE");
            started = true;
            T result = work.run();
            if (transaction.rollbackOnly) {
                throw new SQLException("Transaction rolled back: a nested operation failed or requested a rollback");
            }
            execute(connection, "COMMIT");
            return result;
        } catch (Throwable e) {
            if (started) {
                rollbackQuietly(connection, e);
            }
            throw e;
        } finally {
            activeTransaction.remove();
            closeQuietly(connection);
        }
    }

    private Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
        }
        return connection;
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void rollbackQuietly(Connection connection, Throwable failure) {
        try {
            execute(connection, "ROLLBACK");
        } catch (SQLException rollbackError) {
            // SQLite may already have rolled back on its own; closing the connection finishes the job.
            failure.addSuppressed(rollbackError);
        }
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException e) {
            // The outcome is already decided; a close failure must not turn a commit into an error.
            LOGGER.log(Level.WARNING, "Cannot close transaction connection", e);
        }
    }

    public Path getDatabasePath() {
        return databasePath;
    }

    /** The connection bound to one outermost {@link #inTransaction} call. */
    private static final class Transaction {
        private final Connection connection;
        private final Connection participant;
        private boolean rollbackOnly;

        private Transaction(Connection connection) {
            this.connection = connection;
            this.participant = participantView();
        }

        /**
         * The connection as handed to code running inside the transaction: the outermost
         * inTransaction call owns commit and close, so those calls are ignored, and a rollback
         * marks the whole transaction for rollback.
         */
        private Connection participantView() {
            InvocationHandler handler = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "close", "commit", "setAutoCommit":
                        return null;
                    case "getAutoCommit":
                        return false;
                    case "rollback":
                        if (method.getParameterCount() == 0) {
                            rollbackOnly = true;
                            return null;
                        }
                        break;
                    case "equals":
                        return proxy == args[0];
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    default:
                        break;
                }
                try {
                    return method.invoke(connection, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            };
            return (Connection) Proxy.newProxyInstance(
                DatabaseManager.class.getClassLoader(), new Class<?>[] {Connection.class}, handler);
        }
    }
}
