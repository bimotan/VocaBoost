package com.vocabtrainer.repository;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * The schemas earlier releases created, written out by hand, for upgrade tests. Only those from
 * after schema versioning set {@code user_version}.
 */
enum LegacySchemas {
    /** c49a7d7, the first SQLite release: decks, words, review logs, settings, AI cache. */
    FIRST_RELEASE(false, false, false, false, false, false, false),
    /** c09856a: adds goals and achievements (one set for the whole app) and the dictionary cache. */
    GOALS(true, false, false, false, false, false, false),
    /** 9c488f6: decks can be archived. */
    ARCHIVED_DECKS(true, true, false, false, false, false, false),
    /** 0426f54 up to phase 1: goals and achievements are kept per deck. */
    DECK_SCOPED_GOALS(true, true, true, false, false, false, false),
    /** 51bf259, schema version 4: the last schema before FSRS, words with only the SM-2 schedule. */
    SM2_VERSION_4(true, true, true, true, false, false, false),
    /** 8febeb5, schema version 5: FSRS card state; review logs without kind or direction. */
    FSRS_VERSION_5(true, true, true, true, true, false, false),
    /** 7c7c0ce, schema version 6: review logs with kind and direction, but only the chosen rating. */
    REVIEW_QUEUE_VERSION_6(true, true, true, true, true, true, false),
    /**
     * Schema version 7: review logs with the effective rating and the override. The current schema
     * as long as no later step exists; then it is the schema the next step upgrades.
     */
    EFFECTIVE_RATING_VERSION_7(true, true, true, true, true, true, true);

    private final boolean goals;
    private final boolean archivedDecks;
    private final boolean deckScopedGoals;
    private final boolean versioned;
    private final boolean fsrs;
    private final boolean reviewKinds;
    private final boolean effectiveRating;

    LegacySchemas(boolean goals, boolean archivedDecks, boolean deckScopedGoals, boolean versioned, boolean fsrs,
                  boolean reviewKinds, boolean effectiveRating) {
        this.goals = goals;
        this.archivedDecks = archivedDecks;
        this.deckScopedGoals = deckScopedGoals;
        this.versioned = versioned;
        this.fsrs = fsrs;
        this.reviewKinds = reviewKinds;
        this.effectiveRating = effectiveRating;
    }

    /** Creates this schema in a new database file. */
    void create(Path file) throws SQLException {
        execute(file, statements().toArray(String[]::new));
    }

    boolean hasGoals() {
        return goals;
    }

    boolean hasDeckScopedGoals() {
        return deckScopedGoals;
    }

    List<String> statements() {
        List<String> sql = new ArrayList<>();
        sql.add("CREATE TABLE decks (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL" + (versioned ? "" : " UNIQUE")
            + ", created_at TEXT NOT NULL" + (archivedDecks ? ", archived INTEGER NOT NULL DEFAULT 0" : "") + ")");
        sql.add("""
            CREATE TABLE words (
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
        sql.add("CREATE UNIQUE INDEX idx_words_deck_english ON words(deck_id, english COLLATE NOCASE)");
        sql.add("CREATE INDEX idx_words_due ON words(deck_id, archived, next_review_at)");
        sql.add("""
            CREATE TABLE review_logs (
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
        sql.add("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        sql.add("CREATE TABLE ai_cache (id INTEGER PRIMARY KEY AUTOINCREMENT, cache_key TEXT NOT NULL UNIQUE, "
            + "response TEXT NOT NULL, created_at TEXT NOT NULL)");
        if (goals) {
            sql.add(deckScopedGoals ? DECK_SCOPED_DAILY_GOALS : APP_WIDE_DAILY_GOALS.formatted("daily_goals"));
            sql.add(deckScopedGoals ? DECK_SCOPED_ACHIEVEMENTS : APP_WIDE_ACHIEVEMENTS.formatted("achievements"));
            sql.add("CREATE TABLE dictionary_cache (english TEXT PRIMARY KEY COLLATE NOCASE, payload TEXT NOT NULL, "
                + "source TEXT NOT NULL, created_at TEXT NOT NULL)");
        }
        if (versioned) {
            sql.add("CREATE UNIQUE INDEX idx_decks_active_name ON decks(name) WHERE archived = 0");
            sql.add("CREATE UNIQUE INDEX idx_review_logs_word_time ON review_logs(word_id, reviewed_at, rating)");
            sql.add("CREATE INDEX idx_review_logs_time ON review_logs(reviewed_at)");
            sql.add("PRAGMA user_version = 4");
        }
        if (fsrs) {
            sql.add("ALTER TABLE words ADD COLUMN card_state TEXT");
            sql.add("ALTER TABLE words ADD COLUMN stability REAL NOT NULL DEFAULT 0");
            sql.add("ALTER TABLE words ADD COLUMN difficulty REAL NOT NULL DEFAULT 0");
            sql.add("ALTER TABLE words ADD COLUMN learning_step INTEGER NOT NULL DEFAULT 0");
            sql.add("CREATE INDEX idx_words_without_card_state ON words(id) WHERE card_state IS NULL");
            sql.add("PRAGMA user_version = 5");
        }
        if (reviewKinds) {
            sql.add("ALTER TABLE review_logs ADD COLUMN kind TEXT NOT NULL DEFAULT 'REVIEW'");
            sql.add("ALTER TABLE review_logs ADD COLUMN direction TEXT");
            sql.add("CREATE INDEX idx_words_queue ON words(deck_id, card_state, next_review_at) WHERE archived = 0");
            sql.add("PRAGMA user_version = 6");
        }
        if (effectiveRating) {
            sql.add("ALTER TABLE review_logs ADD COLUMN effective_rating TEXT");
            sql.add("ALTER TABLE review_logs ADD COLUMN overridden INTEGER NOT NULL DEFAULT 0");
            sql.add("PRAGMA user_version = 7");
        }
        return sql;
    }

    /** daily_goals before goals were kept per deck; {@code %s} is the table name. */
    static final String APP_WIDE_DAILY_GOALS = """
        CREATE TABLE %s (
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
        """;

    /** achievements before they were kept per deck; {@code %s} is the table name. */
    static final String APP_WIDE_ACHIEVEMENTS = """
        CREATE TABLE %s (
            code TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            description TEXT NOT NULL,
            unlocked_at TEXT NOT NULL,
            xp_reward INTEGER NOT NULL
        )
        """;

    static final String DECK_SCOPED_DAILY_GOALS = """
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
        """;

    static final String DECK_SCOPED_ACHIEVEMENTS = """
        CREATE TABLE achievements (
            deck_id INTEGER NOT NULL DEFAULT 0,
            code TEXT NOT NULL,
            name TEXT NOT NULL,
            description TEXT NOT NULL,
            unlocked_at TEXT NOT NULL,
            xp_reward INTEGER NOT NULL,
            PRIMARY KEY(deck_id, code)
        )
        """;

    /** Runs statements on a plain connection, outside DatabaseManager, so nothing is migrated. */
    static void execute(Path file, String... statements) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }
}
