package com.vocabtrainer.repository;

import com.vocabtrainer.TestClock;
import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.WordCard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static com.vocabtrainer.repository.SchemaMigrationTest.intQuery;
import static com.vocabtrainer.repository.SchemaMigrationTest.stringColumn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Automatic safety copies of the database (review findings G3 and F8): written with VACUUM INTO
 * before a schema upgrade, before a restore and once a day, the newest kept, and never in the way of
 * what they protect.
 */
class DatabaseSnapshotsTest {
    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    @Test
    void aSnapshotIsACompleteDatabaseAlsoWithUncommittedWorkOfOthersLeftOut() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("vocab.db"));
        Deck deck = new DeckRepository(databaseManager).ensureDefaultDeck();
        new WordRepository(databaseManager).insert(WordCard.createNew(deck.getId(), "lucid", "清晰的"));
        TestClock clock = new TestClock(LocalDateTime.of(2026, 10, 4, 9, 30, 15));

        Path snapshot = new DatabaseSnapshots(databaseManager, clock).take("before-restore");

        assertEquals(tempDir.resolve("snapshots").resolve("vocab-20261004-093015-before-restore.db"), snapshot);
        assertEquals(List.of("ok"), stringColumn(snapshot, "PRAGMA integrity_check"));
        assertEquals(List.of("lucid"), stringColumn(snapshot, "SELECT english FROM words"));
        assertEquals(SchemaMigrations.CURRENT_VERSION, intQuery(snapshot, "PRAGMA user_version"));
        assertFalse(Files.exists(Path.of(snapshot + "-wal")), "a snapshot is one file");
        assertThrows(IllegalArgumentException.class, () -> new DatabaseSnapshots(databaseManager, clock).take("../x"));
    }

    @Test
    void theNewestAreKeptAndOtherFilesInTheFolderAreLeftAlone() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("vocab.db"));
        TestClock clock = new TestClock(LocalDateTime.of(2026, 10, 1, 8, 0));
        DatabaseSnapshots snapshots = new DatabaseSnapshots(databaseManager, clock, 3);
        Files.createDirectories(snapshots.folder());
        Path mine = Files.writeString(snapshots.folder().resolve("my-copy.db"), "the user's own file");
        long written = System.currentTimeMillis() - 100_000;
        for (int i = 0; i < 5; i++) {
            Path snapshot = snapshots.take("daily");
            // File times a second apart, as snapshots written on different days have.
            Files.setLastModifiedTime(snapshot, FileTime.fromMillis(written + i * 1000L));
            clock.advance(Duration.ofDays(1));
        }

        assertEquals(List.of("vocab-20261005-080000-daily.db", "vocab-20261004-080000-daily.db",
            "vocab-20261003-080000-daily.db"), names(snapshots.list()));
        assertTrue(Files.exists(mine));

        // A clock set back (another time zone) writes an older-looking name; that snapshot is still kept.
        clock.set(LocalDateTime.of(2026, 9, 1, 8, 0));
        Path setBack = snapshots.take("before-restore");
        assertTrue(Files.exists(setBack));
        assertEquals(3, snapshots.list().size());
        assertEquals(setBack, snapshots.list().get(0), "the newest by when it was written");
    }

    @Test
    void theDailySnapshotIsWrittenOncePerDay() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("vocab.db"));
        TestClock clock = new TestClock(LocalDateTime.of(2026, 10, 4, 7, 0));
        DatabaseSnapshots snapshots = new DatabaseSnapshots(databaseManager, clock);

        assertTrue(snapshots.takeDailyIfDue().isPresent());
        clock.advance(Duration.ofHours(10));
        assertTrue(snapshots.takeDailyIfDue().isEmpty(), "one a day");
        clock.advance(Duration.ofHours(8));
        Optional<Path> nextDay = snapshots.takeDailyIfDue();

        assertTrue(nextDay.isPresent());
        assertEquals("vocab-20261005-010000-daily.db", nextDay.get().getFileName().toString());
        assertEquals(2, snapshots.list().size());
    }

    @Test
    void aSnapshotThatCannotBeWrittenIsLoggedAndStopsNothing() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("vocab.db"));
        // A file where the folder should be: the folder cannot be created.
        Files.writeString(tempDir.resolve("snapshots"), "in the way");
        DatabaseSnapshots snapshots = new DatabaseSnapshots(databaseManager,
            new TestClock(LocalDateTime.of(2026, 10, 4, 7, 0)));

        try (Capture capture = new Capture()) {
            assertTrue(snapshots.takeQuietly("before-restore").isEmpty());
            assertTrue(snapshots.takeDailyIfDue().isEmpty());
            assertEquals(2, capture.warnings().size(), capture.warnings().toString());
        }
    }

    @Test
    void anExistingDatabaseIsSnapshottedBeforeItIsUpgraded() throws Exception {
        Path file = tempDir.resolve("vocab.db");
        LegacySchemas.EFFECTIVE_RATING_VERSION_7.create(file);
        LegacySchemas.execute(file,
            "INSERT INTO decks(id, name, created_at, archived) VALUES(1, 'GRE', '2026-04-01T08:00:00', 0)",
            "INSERT INTO words(deck_id, english, chinese, added_at, next_review_at, easiness_factor, interval_days,"
                + " repetitions, consecutive_correct, lapses) VALUES(1, 'lucid', '清晰的', '2026-04-01T08:00:00',"
                + " '2026-04-10T08:00:00', 2.5, 3, 2, 2, 0)");

        DatabaseManager databaseManager = databases.open(file);

        List<Path> snapshots = new DatabaseSnapshots(databaseManager, Clock.systemDefaultZone()).list();
        assertEquals(1, snapshots.size());
        assertTrue(snapshots.get(0).getFileName().toString()
            .endsWith("-before-upgrade-v" + SchemaMigrations.CURRENT_VERSION + ".db"), snapshots.toString());
        assertEquals(7, intQuery(snapshots.get(0), "PRAGMA user_version"), "the database as it was");
        assertEquals(List.of("lucid"), stringColumn(snapshots.get(0), "SELECT english FROM words"));
        assertFalse(databaseManager.isNewDatabase());

        // Up to date now: starting again writes no snapshot.
        databaseManager.close();
        DatabaseManager again = databases.open(file);
        assertEquals(1, new DatabaseSnapshots(again, Clock.systemDefaultZone()).list().size());
    }

    @Test
    void aNewDatabaseIsNotSnapshottedAndAFailedSnapshotDoesNotStopAnUpgrade() throws Exception {
        DatabaseManager fresh = databases.open(tempDir.resolve("fresh").resolve("vocab.db"));
        assertTrue(fresh.isNewDatabase());
        assertFalse(Files.exists(tempDir.resolve("fresh").resolve("snapshots")));

        Path file = tempDir.resolve("old").resolve("vocab.db");
        Files.createDirectories(file.getParent());
        LegacySchemas.REVIEW_QUEUE_VERSION_6.create(file);
        Files.writeString(file.resolveSibling("snapshots"), "in the way");
        try (Capture capture = new Capture()) {
            databases.open(file);
            assertEquals(1, capture.warnings().size(), capture.warnings().toString());
        }
        assertEquals(SchemaMigrations.CURRENT_VERSION, intQuery(file, "PRAGMA user_version"));
    }

    private static List<String> names(List<Path> files) {
        return files.stream().map(file -> file.getFileName().toString()).toList();
    }

    /** Collects the warnings the snapshots log and keeps them off the console. */
    private static final class Capture extends Handler implements AutoCloseable {
        private final Logger logger = Logger.getLogger(DatabaseSnapshots.class.getName());
        private final List<LogRecord> records = new CopyOnWriteArrayList<>();

        Capture() {
            logger.addHandler(this);
            logger.setUseParentHandlers(false);
        }

        List<String> warnings() {
            return records.stream().filter(record -> record.getLevel() == Level.WARNING)
                .map(LogRecord::getMessage).toList();
        }

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
            logger.removeHandler(this);
            logger.setUseParentHandlers(true);
        }
    }
}
