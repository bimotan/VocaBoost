package com.vocabtrainer.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Brings a database written by any earlier version of the app up to the current schema.
 *
 * <p>The schema version is stored in {@code PRAGMA user_version}. Version 0 means a database from
 * before versioning: step 1 repeats the schema steps those versions ran on every start (each one
 * does nothing where it already happened), finishes their table rebuilds and recovers what an
 * interrupted rebuild left behind. Every step runs in one transaction together with the version
 * bump, so a crash leaves the database at the previous version, never half migrated. A new
 * database goes through the same steps, so it ends up with exactly the schema of an upgraded one.
 */
final class SchemaMigrations {
    /** The version a fully migrated database has: the last step's. */
    static final int CURRENT_VERSION = 1;

    private static final Logger LOGGER = Logger.getLogger(SchemaMigrations.class.getName());

    /**
     * The note FallbackAiService appends to the mock text when the AI provider fails. Versions
     * that cached the fallback's output stored it in ai_cache; the text must stay exactly what
     * those versions wrote.
     */
    private static final String CACHED_AI_FALLBACK_NOTE = "AI provider failed; mock fallback was used.";

    private static final String DAILY_GOALS_COLUMNS = """
        (
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
        """;

    private static final String ACHIEVEMENTS_COLUMNS = """
        (
            deck_id INTEGER NOT NULL DEFAULT 0,
            code TEXT NOT NULL,
            name TEXT NOT NULL,
            description TEXT NOT NULL,
            unlocked_at TEXT NOT NULL,
            xp_reward INTEGER NOT NULL,
            PRIMARY KEY(deck_id, code)
        )
        """;

    private final Connection connection;
    private final List<Step> steps = List.of(
        new Step(1, "schema of the unversioned releases", false, this::baseline)
    );

    SchemaMigrations(Connection connection) {
        this.connection = connection;
    }

    /** Applies every step the database has not had yet, oldest first. */
    void migrate() throws SQLException {
        int version = userVersion();
        if (version > CURRENT_VERSION) {
            // Written by a newer release: its schema is a superset this version can still use.
            LOGGER.warning("Database schema version " + version + " is newer than this app's "
                + CURRENT_VERSION + "; not migrating");
            return;
        }
        for (Step step : steps) {
            if (step.version() > version) {
                apply(step);
            }
        }
    }

    private void apply(Step step) throws SQLException {
        if (step.foreignKeysOff()) {
            // Only possible outside a transaction; a table rebuild must not cascade-delete rows that
            // reference the table it drops.
            execute("PRAGMA foreign_keys = OFF");
            if (!"0".equals(queryString("PRAGMA foreign_keys"))) {
                throw new SQLException("Cannot turn off foreign keys for schema step " + step.version());
            }
        }
        try {
            execute("BEGIN IMMEDIATE");
            try {
                // Another instance may have migrated while this one waited for the write lock.
                if (userVersion() < step.version()) {
                    step.work().run();
                    execute("PRAGMA user_version = " + step.version());
                }
                execute("COMMIT");
            } catch (Throwable e) {
                try {
                    execute("ROLLBACK");
                } catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                }
                throw e;
            }
        } finally {
            if (step.foreignKeysOff()) {
                execute("PRAGMA foreign_keys = ON");
            }
        }
        LOGGER.info("Database schema is now at version " + step.version() + " (" + step.description() + ")");
    }

    /**
     * Version 1: what the unversioned releases created and migrated at every start, now run once.
     * Old databases get the tables and columns added since they were made, deck-scoped goals and
     * achievements, and the rows an interrupted rebuild left in a {@code *_old} table.
     */
    private void baseline() throws SQLException {
        execute("""
            CREATE TABLE IF NOT EXISTS decks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE,
                created_at TEXT NOT NULL,
                archived INTEGER NOT NULL DEFAULT 0
            )
            """);
        addColumnIfMissing("decks", "archived", "INTEGER NOT NULL DEFAULT 0");
        execute("""
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
        execute("""
            CREATE UNIQUE INDEX IF NOT EXISTS idx_words_deck_english
            ON words(deck_id, english COLLATE NOCASE)
            """);
        execute("""
            CREATE INDEX IF NOT EXISTS idx_words_due
            ON words(deck_id, archived, next_review_at)
            """);
        execute("""
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
        execute("""
            CREATE TABLE IF NOT EXISTS settings (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """);
        execute("""
            CREATE TABLE IF NOT EXISTS ai_cache (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                cache_key TEXT NOT NULL UNIQUE,
                response TEXT NOT NULL,
                created_at TEXT NOT NULL
            )
            """);
        execute("CREATE TABLE IF NOT EXISTS daily_goals " + DAILY_GOALS_COLUMNS);
        execute("CREATE TABLE IF NOT EXISTS achievements " + ACHIEVEMENTS_COLUMNS);
        execute("""
            CREATE TABLE IF NOT EXISTS dictionary_cache (
                english TEXT PRIMARY KEY COLLATE NOCASE,
                payload TEXT NOT NULL,
                source TEXT NOT NULL,
                created_at TEXT NOT NULL
            )
            """);
        moveToDeckScope("daily_goals", DAILY_GOALS_COLUMNS);
        moveToDeckScope("achievements", ACHIEVEMENTS_COLUMNS);
        purgeCachedAiFallbackText();
    }

    /**
     * Gives the goal or achievement table of a pre-multi-deck database a deck_id column, assigning
     * the old rows to the first deck, and merges back rows that an interrupted rebuild of an
     * unversioned release left in {@code <table>_old}. Those releases renamed the table to
     * {@code _old} and copied it back in separate auto-commit statements; when they were stopped in
     * between, the next start created an empty table and never read {@code _old} again.
     */
    private void moveToDeckScope(String table, String columns) throws SQLException {
        long firstDeckId = firstDeckId();
        if (!hasColumn(table, "deck_id")) {
            String rebuilt = table + "_new";
            execute("CREATE TABLE " + rebuilt + " " + columns);
            mergeRows(table, rebuilt, firstDeckId);
            execute("DROP TABLE " + table);
            execute("ALTER TABLE " + rebuilt + " RENAME TO " + table);
        }
        String leftover = table + "_old";
        if (tableExists(leftover)) {
            int recovered = mergeRows(leftover, table, firstDeckId);
            execute("DROP TABLE " + leftover);
            LOGGER.warning("Recovered " + recovered + " row(s) of " + table + " from " + leftover
                + ", left behind by an interrupted upgrade");
        }
    }

    /**
     * Copies the rows of {@code source} into {@code target}, keeping rows already in the target.
     * Rows without a deck_id go to {@code deckId}. Returns the number of rows copied.
     */
    private int mergeRows(String source, String target, long deckId) throws SQLException {
        Set<String> shared = columns(source);
        shared.retainAll(columns(target));
        shared.remove("deck_id");
        String columnList = String.join(", ", shared);
        String deckColumn = hasColumn(source, "deck_id") ? "deck_id" : "?";
        String sql = "INSERT OR IGNORE INTO " + target + "(deck_id, " + columnList + ") "
            + "SELECT " + deckColumn + ", " + columnList + " FROM " + source;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if ("?".equals(deckColumn)) {
                statement.setLong(1, deckId);
            }
            return statement.executeUpdate();
        }
    }

    /**
     * Deletes AI explanations that are really the mock fallback text. Older versions cached it
     * after any provider error, so that word never got a real explanation.
     */
    private void purgeCachedAiFallbackText() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM ai_cache WHERE instr(response, ?) > 0")) {
            statement.setString(1, CACHED_AI_FALLBACK_NOTE);
            int deleted = statement.executeUpdate();
            if (deleted > 0) {
                LOGGER.info("Deleted " + deleted + " cached AI fallback explanation(s) so they are requested again");
            }
        }
    }

    private void addColumnIfMissing(String table, String column, String definition) throws SQLException {
        if (!hasColumn(table, column)) {
            execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    private boolean hasColumn(String table, String column) throws SQLException {
        return columns(table).contains(column.toLowerCase(Locale.ROOT));
    }

    /** Lower-case column names in table order. */
    private Set<String> columns(String table) throws SQLException {
        Set<String> columns = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                columns.add(rs.getString("name").toLowerCase(Locale.ROOT));
            }
        }
        return columns;
    }

    private boolean tableExists(String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, table);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private long firstDeckId() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT id FROM decks ORDER BY id LIMIT 1")) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private int userVersion() throws SQLException {
        return Integer.parseInt(queryString("PRAGMA user_version"));
    }

    private String queryString(String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @FunctionalInterface
    private interface StepWork {
        void run() throws SQLException;
    }

    /**
     * One schema change. {@code foreignKeysOff} is for steps that drop and recreate a table other
     * tables reference; the step must leave every reference valid.
     */
    private record Step(int version, String description, boolean foreignKeysOff, StepWork work) {
    }

}
