package com.vocabtrainer.repository;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.WordCard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ConnectionPoolTest {
    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    private DatabaseManager databaseManager;
    private WordRepository wordRepository;
    private long deckId;

    @BeforeEach
    void setUp() throws Exception {
        databaseManager = databases.open(tempDir.resolve("pool.db"));
        Deck deck = new DeckRepository(databaseManager).ensureDefaultDeck();
        deckId = deck.getId();
        wordRepository = new WordRepository(databaseManager);
    }

    @Test
    void repositoryCallsReuseOneConnectionInsteadOfOpeningOneEach() throws Exception {
        wordRepository.countAll(deckId);
        long opened = databaseManager.connectionsOpened();

        for (int i = 0; i < 50; i++) {
            wordRepository.save(WordCard.createNew(deckId, "word" + (char) ('a' + i % 26) + i, "释义"));
            wordRepository.countAll(deckId);
            wordRepository.findAll(deckId);
        }
        for (int i = 0; i < 20; i++) {
            databaseManager.inTransaction(() -> wordRepository.countDue(deckId, LocalDateTime.now()));
        }

        assertEquals(opened, databaseManager.connectionsOpened());
        assertEquals(1, databaseManager.openConnectionCount());
    }

    @Test
    void everyConnectionIsConfiguredAndTheDatabaseUsesWriteAheadLogging() throws Exception {
        try (Connection first = databaseManager.getConnection();
             Connection second = databaseManager.getConnection()) {
            for (Connection connection : List.of(first, second)) {
                assertEquals("1", pragma(connection, "foreign_keys"));
                assertEquals(String.valueOf(DatabaseManager.BUSY_TIMEOUT_MILLIS), pragma(connection, "busy_timeout"));
                assertEquals("1", pragma(connection, "synchronous"), "synchronous=NORMAL");
                assertEquals("wal", pragma(connection, "journal_mode"));
            }
        }
    }

    @Test
    void closingReturnsTheConnectionAndClosesStatementsTheBorrowerLeftOpen() throws Exception {
        wordRepository.save(WordCard.createNew(deckId, "lucid", "清晰的"));
        SettingsRepository settings = new SettingsRepository(databaseManager);
        try (Connection writer = databaseManager.getConnection()) {
            Connection returned;
            Statement forgotten;
            try (Connection connection = databaseManager.getConnection()) {
                returned = connection;
                forgotten = connection.createStatement();
                // An unfinished cursor holds a read snapshot that the next borrower must not inherit.
                ResultSet unread = forgotten.executeQuery("SELECT english FROM words");
                assertTrue(unread.next());
            }
            long opened = databaseManager.connectionsOpened();

            assertTrue(returned.isClosed());
            assertTrue(forgotten.isClosed());
            assertThrows(SQLException.class, () -> returned.prepareStatement("SELECT 1"));
            try (PreparedStatement insert = writer.prepareStatement("INSERT INTO settings(key, value) VALUES('k', 'v')")) {
                insert.executeUpdate();
            }
            // Reuses the returned connection, which sees the other connection's commit.
            assertEquals("v", settings.find("k").orElseThrow());
            assertEquals(opened, databaseManager.connectionsOpened());
        }
    }

    @Test
    void borrowerThatTurnedOffAutoCommitIsRolledBackBeforeReuse() throws Exception {
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "INSERT INTO settings(key, value) VALUES('unfinished', 'x')")) {
            connection.setAutoCommit(false);
            statement.executeUpdate();
        }

        try (Connection connection = databaseManager.getConnection()) {
            assertTrue(connection.getAutoCommit());
            assertTrue(new SettingsRepository(databaseManager).find("unfinished").isEmpty());
        }
    }

    @Test
    void concurrentThreadsGetTheirOwnConnectionsAndIdleOnesAreCapped() throws Exception {
        int threads = 8;
        CyclicBarrier allHolding = new CyclicBarrier(threads);
        List<Thread> workers = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread worker = new Thread(() -> {
                try (Connection connection = databaseManager.getConnection()) {
                    allHolding.await(10, TimeUnit.SECONDS);
                    try (Statement statement = connection.createStatement()) {
                        statement.executeQuery("SELECT COUNT(*) FROM words").close();
                    }
                } catch (Exception e) {
                    synchronized (failures) {
                        failures.add(e);
                    }
                }
            });
            workers.add(worker);
            worker.start();
        }
        for (Thread worker : workers) {
            worker.join();
        }

        assertTrue(failures.isEmpty(), failures.toString());
        // The threads are gone; their connections went back to the pool or were closed, none is orphaned.
        assertTrue(databaseManager.openConnectionCount() <= 4, "idle: " + databaseManager.openConnectionCount());
        assertTrue(databaseManager.connectionsOpened() >= threads);
    }

    @Test
    void closeReleasesEveryConnectionSoTheFilesCanBeDeleted() throws Exception {
        Path file = tempDir.resolve("closable.db");
        DatabaseManager manager = databases.open(file);
        WordRepository words = new WordRepository(manager);
        long deck = new DeckRepository(manager).ensureDefaultDeck().getId();
        CountDownLatch borrowed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try (Connection connection = manager.getConnection()) {
                borrowed.countDown();
                release.await();
                connection.createStatement().close();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        holder.start();
        borrowed.await();
        words.save(WordCard.createNew(deck, "lucid", "清晰的"));

        manager.close();
        assertEquals(1, manager.openConnectionCount(), "the connection still lent out stays open until returned");
        release.countDown();
        holder.join();

        assertEquals(0, manager.openConnectionCount());
        assertThrows(SQLException.class, manager::getConnection);
        assertThrows(SQLException.class, () -> manager.inTransaction(() -> null));
        assertNoOpenHandle(file);
        // Closing the last connection checkpoints the write-ahead log into the database file.
        assertFalse(Files.exists(Path.of(file + "-wal")));
        Files.delete(file);
        manager.close();
    }

    @Test
    void connectionWhoseRollbackFailedIsClosedInsteadOfPooled() throws Exception {
        assertEquals(1, databaseManager.openConnectionCount());

        assertThrows(SQLException.class, () -> databaseManager.inTransaction(() -> {
            try (Connection connection = databaseManager.getConnection();
                 Statement statement = connection.createStatement()) {
                // Ends the transaction behind inTransaction's back, so its ROLLBACK fails.
                statement.execute("COMMIT");
            }
            throw new SQLException("work failed");
        }));

        assertEquals(0, databaseManager.openConnectionCount());
        assertEquals(0, wordRepository.countAll(deckId));
    }

    private static String pragma(Connection connection, String name) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA " + name)) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    /** On Linux, checks that this process holds no descriptor for the file (Windows would refuse to delete it). */
    private static void assertNoOpenHandle(Path file) throws IOException {
        Path descriptors = Path.of("/proc/self/fd");
        assumeTrue(Files.isDirectory(descriptors), "needs /proc to list open files");
        Path target = file.toAbsolutePath();
        try (Stream<Path> fds = Files.list(descriptors)) {
            List<Path> open = fds.flatMap(fd -> {
                try {
                    return Stream.of(Files.readSymbolicLink(fd));
                } catch (IOException | UnsupportedOperationException e) {
                    return Stream.empty();
                }
            }).filter(path -> path.toString().startsWith(target.toString())).toList();
            assertTrue(open.isEmpty(), "still open: " + open);
        }
    }
}
