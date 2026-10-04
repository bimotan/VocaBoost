package com.vocabtrainer.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    static final int CURRENT_VERSION = 8;

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
        new Step(1, "schema of the unversioned releases", false, this::baseline),
        new Step(2, "deck names unique among active decks only", true, this::uniqueActiveDeckNames),
        new Step(3, "review logs unique per word, time and rating", false, this::uniqueReviewLogs),
        new Step(4, "indexes for statistics", false, this::statisticsIndexes),
        new Step(5, "FSRS card state on words", false, this::fsrsCardState),
        new Step(6, "review log kind and question direction, review queue index", false, this::reviewQueue),
        new Step(7, "review log effective rating and answer check override", false, this::reviewLogEffectiveRating),
        new Step(8, "deck names unique among active decks ignoring case", false, this::activeDeckNamesIgnoringCase)
    );

    SchemaMigrations(Connection connection) {
        this.connection = connection;
    }

    /** Applies every step the database has not had yet, oldest first. */
    void migrate() throws SQLException {
        int version = userVersion();
        if (version > CURRENT_VERSION) {
            // Written by a newer release: leave its schema alone rather than guess how to change it.
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
     * Version 2: decks.name was UNIQUE across archived decks too, so an archived deck blocked its
     * name forever. SQLite cannot drop a column constraint, so the table is rebuilt with the same
     * rows and ids, and a partial index keeps names unique among active decks only.
     */
    private void uniqueActiveDeckNames() throws SQLException {
        if (hasColumnUniqueConstraint("decks", "name")) {
            int brokenReferencesBefore = foreignKeyViolations();
            Long sequence = autoincrementSequence("decks");
            execute("""
                CREATE TABLE decks_new (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    archived INTEGER NOT NULL DEFAULT 0
                )
                """);
            execute("INSERT INTO decks_new(id, name, created_at, archived) SELECT id, name, created_at, archived FROM decks");
            // Foreign keys are off, so this does not cascade to the words that reference decks by id.
            execute("DROP TABLE decks");
            execute("ALTER TABLE decks_new RENAME TO decks");
            if (sequence != null) {
                // Ids of decks deleted in the past are never handed out again.
                keepAutoincrementAtLeast("decks", sequence);
            }
            int brokenReferences = foreignKeyViolations();
            if (brokenReferences > brokenReferencesBefore) {
                throw new SQLException("Rebuilding decks broke " + (brokenReferences - brokenReferencesBefore)
                    + " foreign key reference(s)");
            }
        }
        execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_decks_active_name ON decks(name) WHERE archived = 0");
    }

    /**
     * Version 3: restoring the same backup twice with the old importer inserted every review log
     * again. Rows that repeat an earlier row's word, time, rating and answer are deleted, keeping
     * the first. Then a unique index makes a word's (time, rating) pair identify one review; it
     * also serves lookups by word and the delete cascade from words. If rows remain that share
     * word, time and rating but differ in the answer, nothing more is deleted and the index is
     * created without UNIQUE.
     */
    private void uniqueReviewLogs() throws SQLException {
        int removed;
        try (Statement statement = connection.createStatement()) {
            removed = statement.executeUpdate("""
                DELETE FROM review_logs
                WHERE id NOT IN (
                    SELECT MIN(id) FROM review_logs
                    GROUP BY word_id, reviewed_at, rating, COALESCE(user_answer, '')
                )
                """);
        }
        if (removed > 0) {
            LOGGER.info("Deleted " + removed + " duplicate review log(s) left by restoring a backup more than once");
        }
        String unique = "UNIQUE ";
        if (queryString("""
            SELECT 1 FROM review_logs GROUP BY word_id, reviewed_at, rating HAVING COUNT(*) > 1 LIMIT 1
            """) != null) {
            LOGGER.warning("Some review logs share word, time and rating but differ otherwise; keeping them all");
            unique = "";
        }
        execute("CREATE " + unique + "INDEX IF NOT EXISTS idx_review_logs_word_time"
            + " ON review_logs(word_id, reviewed_at, rating)");
    }

    /**
     * Version 4: the dashboard, the review curve and today's counts filter review logs by time;
     * without this index each of them read the whole table.
     */
    private void statisticsIndexes() throws SQLException {
        execute("CREATE INDEX IF NOT EXISTS idx_review_logs_time ON review_logs(reviewed_at)");
    }

    /**
     * Version 5: words get the FSRS card state (state, stability, difficulty, learning step); the SM-2
     * columns stay and are still written, so older versions keep reading the table. Existing rows get
     * no state ({@code card_state} NULL): the app derives it at startup, by replaying each card's
     * review logs or from its SM-2 schedule, and a row an older version inserts later is derived the
     * same way. The partial index keeps finding such rows cheap once there are none.
     */
    private void fsrsCardState() throws SQLException {
        addColumnIfMissing("words", "card_state", "TEXT");
        addColumnIfMissing("words", "stability", "REAL NOT NULL DEFAULT 0");
        addColumnIfMissing("words", "difficulty", "REAL NOT NULL DEFAULT 0");
        addColumnIfMissing("words", "learning_step", "INTEGER NOT NULL DEFAULT 0");
        execute("CREATE INDEX IF NOT EXISTS idx_words_without_card_state ON words(id) WHERE card_state IS NULL");
    }

    /**
     * Version 6: review logs record what the review was ({@code kind}: {@code LEARN} for the first
     * review of a new card, {@code REVIEW}, or {@code PRACTICE} for a card practiced before it was
     * due) and how the question was asked ({@code direction}: {@code EN_TO_ZH} or {@code ZH_TO_EN}).
     * Existing logs read as reviews of an unknown direction; older versions insert logs the same way.
     * The index serves the review session's separate queries for review and new cards, which only
     * read active words; being partial, it is not mistaken for a way to find words by state alone.
     */
    private void reviewQueue() throws SQLException {
        addColumnIfMissing("review_logs", "kind", "TEXT NOT NULL DEFAULT 'REVIEW'");
        addColumnIfMissing("review_logs", "direction", "TEXT");
        execute("CREATE INDEX IF NOT EXISTS idx_words_queue ON words(deck_id, card_state, next_review_at)"
            + " WHERE archived = 0");
    }

    /**
     * Version 7: review logs record the rating the schedule used ({@code effective_rating}: the
     * chosen rating, capped by the answer check unless the user overrode it) and whether the user
     * overrode the check ({@code overridden}). Existing logs keep no effective rating: it reads as
     * their rating capped by their similarity, which is how the versions that wrote them scheduled
     * them; older versions insert logs the same way.
     */
    private void reviewLogEffectiveRating() throws SQLException {
        addColumnIfMissing("review_logs", "effective_rating", "TEXT");
        addColumnIfMissing("review_logs", "overridden", "INTEGER NOT NULL DEFAULT 0");
    }

    /**
     * Version 8: active deck names were unique only as typed, so "GRE" and "gre" could both exist.
     * Of active decks whose names differ only in case (as SQLite's NOCASE compares them: the letters
     * A to Z), the oldest keeps its name and each newer one, oldest first, gets " (2)", " (3)", ...
     * appended: the first such name no active deck has, ignoring case. Each rename is logged. Then
     * the partial index on active names ignores case. Archived decks keep their names; restoring
     * one is refused while an active deck has its name in any case.
     */
    private void activeDeckNamesIgnoringCase() throws SQLException {
        Map<Long, String> names = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("""
                 SELECT d.id, d.name FROM decks d
                 WHERE d.archived = 0 AND EXISTS (
                     SELECT 1 FROM decks o WHERE o.archived = 0 AND o.id < d.id AND o.name = d.name COLLATE NOCASE)
                 ORDER BY d.id
                 """)) {
            while (rs.next()) {
                names.put(rs.getLong(1), rs.getString(2));
            }
        }
        for (Map.Entry<Long, String> deck : names.entrySet()) {
            String renamed = freeActiveDeckName(deck.getValue(), deck.getKey());
            try (PreparedStatement update = connection.prepareStatement("UPDATE decks SET name = ? WHERE id = ?")) {
                update.setString(1, renamed);
                update.setLong(2, deck.getKey());
                update.executeUpdate();
            }
            LOGGER.warning("Renamed deck " + deck.getKey() + " from \"" + deck.getValue() + "\" to \"" + renamed
                + "\": another active deck has the same name in other upper and lower case");
        }
        execute("DROP INDEX IF EXISTS idx_decks_active_name");
        execute("CREATE UNIQUE INDEX idx_decks_active_name ON decks(name COLLATE NOCASE) WHERE archived = 0");
    }

    /** {@code name} followed by " (2)", " (3)", ...: the first that no other active deck has, ignoring case. */
    private String freeActiveDeckName(String name, long deckId) throws SQLException {
        for (int number = 2; ; number++) {
            String candidate = name + " (" + number + ")";
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM decks WHERE archived = 0 AND id <> ? AND name = ? COLLATE NOCASE")) {
                statement.setLong(1, deckId);
                statement.setString(2, candidate);
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        return candidate;
                    }
                }
            }
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

    /** Whether a UNIQUE constraint in the table definition covers exactly {@code column}. */
    private boolean hasColumnUniqueConstraint(String table, String column) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT 1
            FROM pragma_index_list(?) list
            WHERE list.origin = 'u'
              AND (SELECT COUNT(*) FROM pragma_index_info(list.name)) = 1
              AND (SELECT info.name FROM pragma_index_info(list.name) info) = ?
            """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private int foreignKeyViolations() throws SQLException {
        int violations = 0;
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA foreign_key_check")) {
            while (rs.next()) {
                violations++;
            }
        }
        return violations;
    }

    private Long autoincrementSequence(String table) throws SQLException {
        if (!tableExists("sqlite_sequence")) {
            return null;
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT seq FROM sqlite_sequence WHERE name = ?")) {
            statement.setString(1, table);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private void keepAutoincrementAtLeast(String table, long sequence) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
            "UPDATE sqlite_sequence SET seq = MAX(seq, ?) WHERE name = ?")) {
            update.setLong(1, sequence);
            update.setString(2, table);
            if (update.executeUpdate() > 0) {
                return;
            }
        }
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO sqlite_sequence(name, seq) VALUES(?, ?)")) {
            insert.setString(1, table);
            insert.setLong(2, sequence);
            insert.executeUpdate();
        }
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
