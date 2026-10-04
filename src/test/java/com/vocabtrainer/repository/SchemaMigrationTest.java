package com.vocabtrainer.repository;

import com.vocabtrainer.domain.ReviewLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaMigrationTest {
    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    @Test
    void newDatabaseIsCreatedAtTheCurrentVersion() throws Exception {
        Path file = tempDir.resolve("new.db");
        databases.open(file);

        assertEquals(SchemaMigrations.CURRENT_VERSION, intQuery(file, "PRAGMA user_version"));
    }

    @Test
    void upgradesPreMultiDeckDatabaseWithoutLosingData() throws Exception {
        Path file = tempDir.resolve("pre-multi-deck.db");
        LegacySchemas.GOALS.create(file);
        seedPreMultiDeckData(file);

        DatabaseManager databaseManager = databases.open(file);

        assertEquals(SchemaMigrations.CURRENT_VERSION, intQuery(file, "PRAGMA user_version"));
        assertEquals(0, intQuery(file, "SELECT COUNT(*) FROM decks WHERE archived <> 0"));
        assertEquals(3, intQuery(file, "SELECT COUNT(*) FROM words"));
        assertEquals(3, intQuery(file, "SELECT COUNT(*) FROM review_logs"));
        // App-wide goal history and achievements now belong to the first deck.
        assertEquals(30, intQuery(file, "SELECT COUNT(*) FROM daily_goals WHERE deck_id = 1"));
        GoalRepository goals = new GoalRepository(databaseManager);
        assertEquals(600, intQuery(file, "SELECT SUM(reviewed_count) FROM daily_goals WHERE deck_id = 1"));
        assertEquals(4500, goals.totalXp(1));
        assertEquals(List.of("first_review", "streak_3"),
            stringColumn(file, "SELECT code FROM achievements WHERE deck_id = 1 ORDER BY code"));
        assertEquals("2", new SettingsRepository(databaseManager).find("ui.lastDeckId").orElseThrow());
        assertEquals(List.of("explain:v1:candid"), stringColumn(file, "SELECT cache_key FROM ai_cache"));
        assertEquals(1, intQuery(file, "SELECT COUNT(*) FROM dictionary_cache"));
        assertNoRebuildLeftovers(file);
        assertDatabaseIsConsistent(file);
    }

    @ParameterizedTest
    @EnumSource(LegacySchemas.class)
    void everyEarlierSchemaUpgradesToExactlyTheSchemaOfANewDatabase(LegacySchemas legacy) throws Exception {
        Path fresh = tempDir.resolve("fresh.db");
        databases.open(fresh);
        Path upgraded = tempDir.resolve(legacy.name() + ".db");
        legacy.create(upgraded);

        databases.open(upgraded);

        assertEquals(describeSchema(fresh), describeSchema(upgraded));
        assertEquals(SchemaMigrations.CURRENT_VERSION, intQuery(upgraded, "PRAGMA user_version"));
    }

    @Test
    void recoversGoalsAndAchievementsWhenAnUpgradeStoppedBeforeCopyingThem() throws Exception {
        Path file = tempDir.resolve("stopped-after-rename.db");
        LegacySchemas.ARCHIVED_DECKS.create(file);
        seedPreMultiDeckData(file);
        // What the unversioned deck-scope upgrade had done when the app was killed after its first statement.
        LegacySchemas.execute(file,
            "ALTER TABLE daily_goals RENAME TO daily_goals_old",
            "ALTER TABLE achievements RENAME TO achievements_old");

        DatabaseManager databaseManager = databases.open(file);

        GoalRepository goals = new GoalRepository(databaseManager);
        assertEquals(30, goals.findAll(1).size());
        assertEquals(4500, goals.totalXp(1));
        assertEquals(List.of("first_review", "streak_3"),
            stringColumn(file, "SELECT code FROM achievements WHERE deck_id = 1 ORDER BY code"));
        assertNoRebuildLeftovers(file);
    }

    @Test
    void mergesHistoryOrphanedByAnInterruptedUpgradeIntoWhatWasRecordedSince() throws Exception {
        Path file = tempDir.resolve("orphaned.db");
        LegacySchemas.ARCHIVED_DECKS.create(file);
        seedPreMultiDeckData(file);
        LegacySchemas.execute(file,
            "ALTER TABLE daily_goals RENAME TO daily_goals_old",
            "ALTER TABLE achievements RENAME TO achievements_old",
            // The next start of that release created empty deck-scoped tables and skipped the copy...
            LegacySchemas.DECK_SCOPED_DAILY_GOALS,
            LegacySchemas.DECK_SCOPED_ACHIEVEMENTS,
            // ...and the user kept studying: a day that is also in the orphaned history, and a new day.
            "INSERT INTO daily_goals(deck_id, goal_date, review_goal, new_word_goal, session_goal, reviewed_count,"
                + " correct_count, xp_earned) VALUES(1, '2026-04-30', 20, 5, 10, 3, 3, 40)",
            "INSERT INTO daily_goals(deck_id, goal_date, review_goal, new_word_goal, session_goal, reviewed_count,"
                + " correct_count, xp_earned) VALUES(1, '2026-05-01', 20, 5, 10, 2, 1, 25)",
            "INSERT INTO achievements(deck_id, code, name, description, unlocked_at, xp_reward)"
                + " VALUES(1, 'first_review', 'First Review', 'again', '2026-05-01T10:00:00', 10)");

        DatabaseManager databaseManager = databases.open(file);

        GoalRepository goals = new GoalRepository(databaseManager);
        assertEquals(31, goals.findAll(1).size());
        // The row recorded after the interruption wins for the day both have.
        assertEquals(40, goals.find(1, java.time.LocalDate.of(2026, 4, 30)).orElseThrow().xpEarned());
        assertEquals(4500 - 150 + 40 + 25, goals.totalXp(1));
        assertEquals(List.of("first_review", "streak_3"),
            stringColumn(file, "SELECT code FROM achievements WHERE deck_id = 1 ORDER BY code"));
        assertEquals("2026-05-01T10:00:00",
            stringColumn(file, "SELECT unlocked_at FROM achievements WHERE code = 'first_review'").get(0));
        assertNoRebuildLeftovers(file);
    }

    @Test
    void rebuildsDecksSoArchivedNamesCanBeReusedWithoutTouchingWordsOrIds() throws Exception {
        Path file = tempDir.resolve("deck-names.db");
        LegacySchemas.DECK_SCOPED_GOALS.create(file);
        seedPreMultiDeckData(file);
        LegacySchemas.execute(file,
            // Deck 3 was deleted once, so AUTOINCREMENT is ahead of the highest id.
            "INSERT INTO decks(id, name, created_at, archived) VALUES(4, 'TOEFL', '2026-04-03T08:00:00', 1)",
            "UPDATE sqlite_sequence SET seq = 7 WHERE name = 'decks'",
            word(4, "laconic", "简洁的"));

        DatabaseManager databaseManager = databases.open(file);

        assertEquals(List.of("1|默认词库|0", "2|GRE|0", "4|TOEFL|1"),
            stringColumn(file, "SELECT id || '|' || name || '|' || archived FROM decks ORDER BY id"));
        // The rebuild dropped the old table with foreign keys off: no word or log was cascade-deleted.
        assertEquals(4, intQuery(file, "SELECT COUNT(*) FROM words"));
        assertEquals(3, intQuery(file, "SELECT COUNT(*) FROM review_logs"));
        assertDatabaseIsConsistent(file);
        assertEquals("1", stringColumn(file, "SELECT \"unique\" FROM pragma_index_list('decks')"
            + " WHERE name = 'idx_decks_active_name'").get(0));

        DeckRepository decks = new DeckRepository(databaseManager);
        assertEquals(8, decks.create("TOEFL").getId(), "ids of deleted decks are not reused");
        assertThrows(SQLException.class, () -> decks.create("GRE"));
        assertThrows(SQLException.class, () -> decks.restore(4));
        // Foreign keys are back on for the pooled connections.
        try (Connection connection = databaseManager.getConnection();
             Statement statement = connection.createStatement()) {
            assertThrows(SQLException.class, () -> statement.executeUpdate(word(99, "orphan", "孤儿")));
        }
    }

    @Test
    void renamesActiveDecksWhoseNamesDifferOnlyInCaseAndIndexesNamesIgnoringCase() throws Exception {
        Path file = tempDir.resolve("deck-case.db");
        LegacySchemas.EFFECTIVE_RATING_VERSION_7.create(file);
        LegacySchemas.execute(file,
            "INSERT INTO decks(id, name, created_at, archived) VALUES(1, 'GRE', '2026-04-01T08:00:00', 0)",
            "INSERT INTO decks(id, name, created_at, archived) VALUES(2, 'gre', '2026-04-02T08:00:00', 0)",
            "INSERT INTO decks(id, name, created_at, archived) VALUES(3, 'Gre', '2026-04-03T08:00:00', 0)",
            "INSERT INTO decks(id, name, created_at, archived) VALUES(4, 'gre (2)', '2026-04-04T08:00:00', 0)",
            "INSERT INTO decks(id, name, created_at, archived) VALUES(5, 'gre', '2026-04-05T08:00:00', 1)",
            "INSERT INTO decks(id, name, created_at, archived) VALUES(6, '托福', '2026-04-06T08:00:00', 0)",
            word(2, "lucid", "清晰的"));

        DatabaseManager databaseManager = databases.open(file);

        // The oldest keeps its name; the first free " (n)" ignoring case goes to each newer one, oldest first.
        assertEquals(List.of("1|GRE|0", "2|gre (3)|0", "3|Gre (4)|0", "4|gre (2)|0", "5|gre|1", "6|托福|0"),
            stringColumn(file, "SELECT id || '|' || name || '|' || archived FROM decks ORDER BY id"));
        assertEquals(List.of("2|lucid"), stringColumn(file, "SELECT deck_id || '|' || english FROM words"));
        assertEquals(List.of("1|NOCASE"), stringColumn(file, "SELECT il.\"unique\" || '|' || ix.coll"
            + " FROM pragma_index_list('decks') il, pragma_index_xinfo(il.name) ix"
            + " WHERE il.name = 'idx_decks_active_name' AND ix.key = 1"));
        DeckRepository decks = new DeckRepository(databaseManager);
        assertThrows(SQLException.class, () -> decks.create("gRE"));
        assertThrows(SQLException.class, () -> decks.restore(5));
        assertDatabaseIsConsistent(file);
    }

    @Test
    void collapsesReviewLogsDuplicatedByRepeatedRestores() throws Exception {
        Path file = tempDir.resolve("duplicate-logs.db");
        LegacySchemas.DECK_SCOPED_GOALS.create(file);
        seedPreMultiDeckData(file);
        String copyOriginals = "INSERT INTO review_logs(word_id, reviewed_at, user_answer, correct_answer, similarity,"
            + " rating, elapsed_millis) SELECT word_id, reviewed_at, COALESCE(user_answer, ''), correct_answer,"
            + " similarity, rating, elapsed_millis FROM review_logs WHERE id <= 4";
        LegacySchemas.execute(file,
            // An original without an answer; the old importer wrote it back as ''.
            "INSERT INTO review_logs(id, word_id, reviewed_at, user_answer, correct_answer, similarity, rating,"
                + " elapsed_millis) VALUES(4, 2, '2026-04-05T09:00:00', NULL, '减轻', 0.0, 'AGAIN', 0)",
            // Every restore of the same backup added all logs again.
            copyOriginals, copyOriginals,
            // A separate review a second later is not a duplicate.
            "INSERT INTO review_logs(word_id, reviewed_at, user_answer, correct_answer, similarity, rating,"
                + " elapsed_millis) VALUES(1, '2026-04-03T09:00:01', '清晰的', '清晰的', 1.0, 'GOOD', 900)");
        assertEquals(13, intQuery(file, "SELECT COUNT(*) FROM review_logs"));

        DatabaseManager databaseManager = databases.open(file);

        assertEquals(List.of("1", "2", "3", "4", "13"), stringColumn(file, "SELECT id FROM review_logs ORDER BY id"));
        assertEquals("1", stringColumn(file, "SELECT \"unique\" FROM pragma_index_list('review_logs')"
            + " WHERE name = 'idx_review_logs_word_time'").get(0));
        assertThrows(SQLException.class, () -> LegacySchemas.execute(file, copyOriginals));
        ReviewLogRepository logs = new ReviewLogRepository(databaseManager);
        ReviewLog again = logs.findByDeck(1).get(0);
        again.setId(0);
        assertEquals(0, logs.insertAllIfAbsent(List.of(again)));
        assertEquals(5, logs.countAll());
    }

    @Test
    void keepsReviewLogsThatShareWordTimeAndRatingButDifferInTheAnswer() throws Exception {
        Path file = tempDir.resolve("same-moment.db");
        LegacySchemas.DECK_SCOPED_GOALS.create(file);
        seedPreMultiDeckData(file);
        LegacySchemas.execute(file, "INSERT INTO review_logs(word_id, reviewed_at, user_answer, correct_answer,"
            + " similarity, rating, elapsed_millis) VALUES(1, '2026-04-03T09:00:00', '明亮的', '清晰的', 0.4, 'GOOD', 700)");

        DatabaseManager databaseManager = databases.open(file);

        assertEquals(4, intQuery(file, "SELECT COUNT(*) FROM review_logs"));
        assertEquals("0", stringColumn(file, "SELECT \"unique\" FROM pragma_index_list('review_logs')"
            + " WHERE name = 'idx_review_logs_word_time'").get(0));
        ReviewLogRepository logs = new ReviewLogRepository(databaseManager);
        ReviewLog again = logs.findByDeck(1).get(0);
        again.setId(0);
        assertEquals(0, logs.insertAllIfAbsent(List.of(again)), "restores still skip a review that is already there");
    }

    @Test
    void failedStepChangesNothingAndIsRetriedOnTheNextStart() throws Exception {
        Path file = tempDir.resolve("failing.db");
        LegacySchemas.GOALS.create(file);
        seedPreMultiDeckData(file);
        // Makes the daily_goals rebuild fail after the step already added decks.archived.
        LegacySchemas.execute(file, "CREATE TABLE daily_goals_new (blocker INTEGER)");
        Map<String, List<String>> before = describeSchema(file);

        DatabaseManager failing = databases.track(new DatabaseManager(file));
        assertThrows(SQLException.class, failing::initialize);

        assertEquals(0, intQuery(file, "PRAGMA user_version"));
        assertEquals(before, describeSchema(file));
        assertEquals(30, intQuery(file, "SELECT COUNT(*) FROM daily_goals"));
        assertEquals(0, failing.openConnectionCount(), "the failed connection is not pooled");

        LegacySchemas.execute(file, "DROP TABLE daily_goals_new");
        DatabaseManager databaseManager = databases.open(file);
        assertEquals(SchemaMigrations.CURRENT_VERSION, intQuery(file, "PRAGMA user_version"));
        assertEquals(4500, new GoalRepository(databaseManager).totalXp(1));
    }

    @Test
    void initializingTwiceChangesNothing() throws Exception {
        Path file = tempDir.resolve("twice.db");
        LegacySchemas.GOALS.create(file);
        seedPreMultiDeckData(file);
        databases.open(file);
        Map<String, List<String>> once = describeSchema(file);
        int xp = intQuery(file, "SELECT SUM(xp_earned) FROM daily_goals");

        databases.open(file);

        assertEquals(once, describeSchema(file));
        assertEquals(xp, intQuery(file, "SELECT SUM(xp_earned) FROM daily_goals"));
    }

    @Test
    void databaseFromANewerReleaseIsOpenedWithoutMigrating() throws Exception {
        Path file = tempDir.resolve("newer.db");
        databases.open(file);
        LegacySchemas.execute(file, "PRAGMA user_version = 99");

        databases.open(file);

        assertEquals(99, intQuery(file, "PRAGMA user_version"));
    }

    /** Two decks, three words with logs, 30 days of app-wide goals (600 reviews, 4500 XP), two achievements. */
    static void seedPreMultiDeckData(Path file) throws SQLException {
        List<String> sql = new ArrayList<>(List.of(
            "INSERT INTO decks(name, created_at) VALUES('默认词库', '2026-04-01T08:00:00')",
            "INSERT INTO decks(name, created_at) VALUES('GRE', '2026-04-02T08:00:00')",
            word(1, "lucid", "清晰的"), word(1, "abate", "减轻"), word(2, "candid", "坦率的"),
            "INSERT INTO review_logs(word_id, reviewed_at, user_answer, correct_answer, similarity, rating,"
                + " elapsed_millis) VALUES(1, '2026-04-03T09:00:00', '清晰的', '清晰的', 1.0, 'GOOD', 1200)",
            "INSERT INTO review_logs(word_id, reviewed_at, user_answer, correct_answer, similarity, rating,"
                + " elapsed_millis) VALUES(1, '2026-04-04T09:00:00.5', '', '清晰的', 0.0, 'AGAIN', 800)",
            "INSERT INTO review_logs(word_id, reviewed_at, user_answer, correct_answer, similarity, rating,"
                + " elapsed_millis) VALUES(3, '2026-04-04T09:01:00', '坦率', '坦率的', 0.8, 'HARD', 900)",
            "INSERT INTO achievements(code, name, description, unlocked_at, xp_reward)"
                + " VALUES('first_review', 'First Review', 'first', '2026-04-03T09:00:00', 10)",
            "INSERT INTO achievements(code, name, description, unlocked_at, xp_reward)"
                + " VALUES('streak_3', '3-Day Streak', 'streak', '2026-04-05T09:00:00', 20)",
            "INSERT INTO settings(key, value) VALUES('ui.lastDeckId', '2')",
            "INSERT INTO ai_cache(cache_key, response, created_at) VALUES('explain:v1:lucid',"
                + " 'mock text' || char(10) || 'AI provider failed; mock fallback was used.', '2026-04-03T09:00:00')",
            "INSERT INTO ai_cache(cache_key, response, created_at)"
                + " VALUES('explain:v1:candid', 'candid 指坦率的。', '2026-04-03T09:00:00')",
            "INSERT INTO dictionary_cache(english, payload, source, created_at)"
                + " VALUES('lucid', '{}', 'mock', '2026-04-03T09:00:00')"
        ));
        for (int day = 1; day <= 30; day++) {
            sql.add("INSERT INTO daily_goals(goal_date, review_goal, new_word_goal, session_goal, reviewed_count,"
                + " correct_count, new_words_count, xp_earned, completed)"
                + " VALUES('2026-04-%02d', 20, 5, 10, 20, 15, 5, 150, 1)".formatted(day));
        }
        LegacySchemas.execute(file, sql.toArray(String[]::new));
    }

    private static String word(long deckId, String english, String chinese) {
        return "INSERT INTO words(deck_id, english, chinese, added_at, next_review_at, easiness_factor, interval_days,"
            + " repetitions, consecutive_correct, lapses) VALUES(" + deckId + ", '" + english + "', '" + chinese
            + "', '2026-04-01T08:00:00', '2026-04-10T08:00:00', 2.5, 3, 2, 2, 0)";
    }

    private static void assertNoRebuildLeftovers(Path file) throws SQLException {
        for (String table : stringColumn(file, "SELECT name FROM sqlite_master WHERE type = 'table'")) {
            assertFalse(table.endsWith("_old") || table.endsWith("_new"), "left behind: " + table);
        }
    }

    static void assertDatabaseIsConsistent(Path file) throws SQLException {
        assertEquals(List.of("ok"), stringColumn(file, "PRAGMA integrity_check"));
        assertTrue(stringColumn(file, "PRAGMA foreign_key_check").isEmpty());
    }

    /**
     * Every table's columns and every index's definition, by name. Compared instead of the stored
     * CREATE text, which differs in layout when a column was added with ALTER TABLE.
     */
    static Map<String, List<String>> describeSchema(Path file) throws SQLException {
        Map<String, List<String>> schema = new TreeMap<>();
        for (String table : stringColumn(file,
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name <> 'sqlite_sequence'")) {
            schema.put("table " + table, rows(file, "SELECT name, type, \"notnull\", dflt_value, pk"
                + " FROM pragma_table_info('" + table + "') ORDER BY cid"));
            schema.put("foreign keys " + table, rows(file, "SELECT \"table\", \"from\", \"to\", on_delete"
                + " FROM pragma_foreign_key_list('" + table + "') ORDER BY id, seq"));
            for (String index : stringColumn(file, "SELECT name FROM pragma_index_list('" + table + "')")) {
                List<String> definition = new ArrayList<>(rows(file, "SELECT \"unique\", origin, partial"
                    + " FROM pragma_index_list('" + table + "') WHERE name = '" + index + "'"));
                definition.addAll(rows(file, "SELECT name, coll, \"desc\" FROM pragma_index_xinfo('" + index
                    + "') WHERE key = 1 ORDER BY seqno"));
                // A partial index's condition is only in its CREATE text.
                definition.addAll(stringColumn(file, "SELECT substr(sql, instr(upper(sql), ' WHERE ')) FROM sqlite_master"
                    + " WHERE type = 'index' AND name = '" + index + "' AND instr(upper(sql), ' WHERE ') > 0"));
                schema.put("index " + (index.startsWith("sqlite_autoindex") ? table + " auto " + definition : index),
                    definition);
            }
        }
        return schema;
    }

    static int intQuery(Path file, String sql) throws SQLException {
        return Integer.parseInt(stringColumn(file, sql).get(0));
    }

    static List<String> stringColumn(Path file, String sql) throws SQLException {
        return rows(file, sql);
    }

    /** Rows as their columns joined with '|'; read on a plain connection, outside DatabaseManager. */
    private static List<String> rows(Path file, String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                List<String> values = new ArrayList<>();
                for (int i = 1; i <= columns; i++) {
                    values.add(String.valueOf(rs.getString(i)));
                }
                rows.add(String.join("|", values));
            }
        }
        return rows;
    }
}
