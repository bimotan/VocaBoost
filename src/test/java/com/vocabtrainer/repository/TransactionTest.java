package com.vocabtrainer.repository;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.WordCard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionTest {
    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    private DatabaseManager databaseManager;
    private WordRepository wordRepository;
    private long deckId;

    @BeforeEach
    void setUp() throws Exception {
        databaseManager = databases.open(tempDir.resolve("transactions.db"));
        Deck deck = new DeckRepository(databaseManager).ensureDefaultDeck();
        deckId = deck.getId();
        wordRepository = new WordRepository(databaseManager);
    }

    @Test
    void commitsAllWritesWhenWorkSucceeds() throws Exception {
        int saved = databaseManager.inTransaction(() -> {
            wordRepository.save(word("lucid"));
            wordRepository.save(word("abate"));
            return 2;
        });

        assertEquals(2, saved);
        assertEquals(2, wordRepository.countAll(deckId));
    }

    @Test
    void nestedTransactionJoinsOuterAndRollsBackWithIt() throws Exception {
        assertThrows(IllegalStateException.class, () -> databaseManager.inTransaction(() -> {
            wordRepository.save(word("outer"));
            databaseManager.inTransaction(() -> wordRepository.save(word("inner")));
            throw new IllegalStateException("outer work failed after the nested call returned");
        }));

        assertEquals(0, wordRepository.countAll(deckId));
    }

    @Test
    void failedNestedTransactionRollsBackOuterEvenWhenCaught() throws Exception {
        assertThrows(SQLException.class, () -> databaseManager.inTransaction(() -> {
            wordRepository.save(word("outer"));
            try {
                databaseManager.inTransaction(() -> {
                    wordRepository.save(word("inner"));
                    throw new SQLException("nested work failed");
                });
            } catch (SQLException ignored) {
                // Swallowing the nested failure must not commit half of the work.
            }
            return null;
        }));

        assertEquals(0, wordRepository.countAll(deckId));
    }

    @Test
    void rollbackRequestedThroughJoinedConnectionRollsBackEverything() throws Exception {
        assertThrows(SQLException.class, () -> databaseManager.inTransaction(() -> {
            wordRepository.save(word("lucid"));
            try (Connection connection = databaseManager.getConnection()) {
                connection.rollback();
            }
            return null;
        }));

        assertEquals(0, wordRepository.countAll(deckId));
    }

    @Test
    void connectionInsideTransactionIsNotClosedByRepositoryTryWithResources() throws Exception {
        databaseManager.inTransaction(() -> {
            Connection joined;
            try (Connection connection = databaseManager.getConnection()) {
                joined = connection;
            }
            wordRepository.save(word("lucid"));
            wordRepository.save(word("abate"));

            assertFalse(joined.isClosed());
            assertFalse(joined.getAutoCommit());
            // Same connection: the transaction sees its own writes...
            assertEquals(2, wordRepository.countAll(deckId));
            // ...while other threads, on their own connections, do not see them before the commit.
            assertEquals(0, CompletableFuture.supplyAsync(this::countFromOtherConnection).join());
            return null;
        });

        assertEquals(2, wordRepository.countAll(deckId));
        assertEquals(2, countFromOtherConnection());
    }

    @Test
    void connectionsOutsideTransactionAreIndependent() throws Exception {
        Connection first = databaseManager.getConnection();
        Connection second = databaseManager.getConnection();
        try {
            assertTrue(first.getAutoCommit());
            first.close();
            assertTrue(first.isClosed());
            assertFalse(second.isClosed());
        } finally {
            second.close();
        }
    }

    @Test
    void bulkInsertJoinsOuterTransaction() throws Exception {
        assertThrows(IllegalStateException.class, () -> databaseManager.inTransaction(() -> {
            wordRepository.insertAll(List.of(word("lucid"), word("abate")));
            throw new IllegalStateException("caller failed after the bulk insert");
        }));

        assertEquals(0, wordRepository.countAll(deckId));
    }

    @Test
    void bulkInsertIsAllOrNothing() throws Exception {
        wordRepository.save(word("abate"));

        assertThrows(SQLException.class,
            () -> wordRepository.insertAll(List.of(word("lucid"), word("abate"))));

        assertEquals(1, wordRepository.countAll(deckId));
    }

    @Test
    void transactionTakesWriteLockBeforeFirstStatement() throws Exception {
        databaseManager.inTransaction(() -> {
            try (Connection other = DriverManager.getConnection("jdbc:sqlite:" + databaseManager.getDatabasePath());
                 Statement statement = other.createStatement()) {
                statement.execute("PRAGMA busy_timeout = 0");
                assertThrows(SQLException.class,
                    () -> statement.executeUpdate("INSERT INTO settings(key, value) VALUES('probe', '1')"));
            }
            return null;
        });
    }

    private int countFromOtherConnection() {
        try {
            return wordRepository.countAll(deckId);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private WordCard word(String english) {
        return WordCard.createNew(deckId, english, "释义");
    }
}
