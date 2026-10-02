package com.vocabtrainer.service;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.DeckRepository;
import com.vocabtrainer.repository.GoalRepository;
import com.vocabtrainer.repository.ReviewLogRepository;
import com.vocabtrainer.repository.SettingsRepository;
import com.vocabtrainer.repository.TestDatabases;
import com.vocabtrainer.repository.WordRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Restarts the app against the same database file the way VocabTrainerApp does: fresh
 * repositories and services, resolve the startup deck, then the one-time starter import.
 */
class StartupDeckTest {
    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    @Test
    void brandNewDatabaseImportsStarterExactlyOnce() throws Exception {
        Launch first = launch();

        assertEquals(DeckRepository.DEFAULT_DECK_NAME, first.deck().getName());
        assertTrue(first.starter().isPresent());
        int starterCount = first.starter().get().importedCount();
        assertTrue(starterCount >= 100, "starter words imported: " + starterCount);
        assertEquals(starterCount, first.words().countAll(first.deck().getId()));
        assertTrue(first.settings().isStarterImported());

        WordCard removed = first.words().findAll(first.deck().getId()).get(0);
        first.words().deleteById(removed.getId());

        Launch second = launch();
        assertEquals(first.deck().getId(), second.deck().getId());
        assertTrue(second.starter().isEmpty());
        assertEquals(starterCount - 1, second.words().countAll(second.deck().getId()));
        assertTrue(second.words().findByEnglish(second.deck().getId(), removed.getEnglish()).isEmpty());
    }

    @Test
    void archivedDefaultDeckDoesNotBreakNextStartup() throws Exception {
        Launch first = launch();
        Deck gre = first.deckService().createDeck("My GRE");
        first.deckService().archiveDeck(first.deck().getId());

        Launch second = launch();

        assertEquals(gre.getId(), second.deck().getId());
        assertFalse(second.deck().isArchived());
        assertTrue(second.starter().isEmpty());
        assertEquals(2, second.decks().findAllIncludingArchived().size());
        assertEquals(0, second.words().countAll(gre.getId()));
    }

    @Test
    void lastUsedDeckIsArchivedFallsBackToOldestActiveDeck() throws Exception {
        Launch first = launch();
        Deck gre = first.deckService().createDeck("My GRE");
        Deck toefl = first.deckService().createDeck("TOEFL");
        first.settings().saveLastDeckId(toefl.getId());
        first.deckService().archiveDeck(toefl.getId());

        Launch second = launch();

        assertEquals(first.deck().getId(), second.deck().getId());
        assertEquals(first.deck().getId(), second.settings().getLastDeckId().orElseThrow());
        assertTrue(second.decks().findById(toefl.getId()).orElseThrow().isArchived());
        assertFalse(second.decks().findById(gre.getId()).orElseThrow().isArchived());
    }

    @Test
    void renamedDefaultDeckIsReopenedWithoutNewDeckOrStarterImport() throws Exception {
        Launch first = launch();
        int starterCount = first.words().countAll(first.deck().getId());
        first.deckService().renameDeck(first.deck().getId(), "GRE 核心");

        Launch second = launch();

        assertEquals(first.deck().getId(), second.deck().getId());
        assertEquals("GRE 核心", second.deck().getName());
        assertEquals(1, second.decks().findAllIncludingArchived().size());
        assertTrue(second.decks().findAnyByName(DeckRepository.DEFAULT_DECK_NAME).isEmpty());
        assertTrue(second.starter().isEmpty());
        assertEquals(starterCount, second.words().countAll(second.deck().getId()));
    }

    @Test
    void emptiedDeckIsNotRefilledOnRestart() throws Exception {
        Launch first = launch();
        for (WordCard word : first.words().findAll(first.deck().getId())) {
            first.words().deleteById(word.getId());
        }

        Launch second = launch();

        assertTrue(second.starter().isEmpty());
        assertEquals(0, second.words().countAll(second.deck().getId()));
    }

    @Test
    void lastUsedDeckIsReopened() throws Exception {
        Launch first = launch();
        Deck gre = first.deckService().createDeck("My GRE");
        first.settings().saveLastDeckId(gre.getId());

        Launch second = launch();

        assertEquals(gre.getId(), second.deck().getId());
        assertEquals("My GRE", second.deck().getName());
    }

    @Test
    void databaseFromBeforeTheFlagWithWordsIsNotSeededAgain() throws Exception {
        DatabaseManager databaseManager = initializedDatabase();
        DeckRepository deckRepository = new DeckRepository(databaseManager);
        WordRepository wordRepository = new WordRepository(databaseManager);
        Deck legacyDeck = deckRepository.ensureDefaultDeck();
        Deck otherDeck = deckRepository.create("Other");
        wordRepository.insert(WordCard.createNew(otherDeck.getId(), "lucid", "清晰的"));

        Launch launch = launch();

        assertEquals(legacyDeck.getId(), launch.deck().getId());
        assertTrue(launch.starter().isEmpty());
        assertTrue(launch.settings().isStarterImported());
        assertEquals(0, launch.words().countAll(legacyDeck.getId()));
    }

    @Test
    void databaseFromBeforeTheFlagWithOnlyGoalHistoryIsNotSeededAgain() throws Exception {
        DatabaseManager databaseManager = initializedDatabase();
        Deck legacyDeck = new DeckRepository(databaseManager).ensureDefaultDeck();
        new GoalRepository(databaseManager).ensure(legacyDeck.getId(), LocalDate.now(), 20, 5, 10);

        Launch launch = launch();

        assertTrue(launch.starter().isEmpty());
        assertEquals(0, launch.words().countAll(legacyDeck.getId()));
    }

    @Test
    void allDecksArchivedRestoresNewestDeck() throws Exception {
        DatabaseManager databaseManager = initializedDatabase();
        DeckRepository deckRepository = new DeckRepository(databaseManager);
        Deck defaultDeck = deckRepository.ensureDefaultDeck();
        Deck newest = deckRepository.create("Newest");
        deckRepository.archive(defaultDeck.getId());
        deckRepository.archive(newest.getId());

        Launch launch = launch();

        assertEquals(newest.getId(), launch.deck().getId());
        assertFalse(launch.deck().isArchived());
        assertEquals(2, launch.decks().findAllIncludingArchived().size());
    }

    private DatabaseManager initializedDatabase() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("startup.db"));
        return databaseManager;
    }

    private Launch launch() throws Exception {
        DatabaseManager databaseManager = initializedDatabase();
        DeckRepository deckRepository = new DeckRepository(databaseManager);
        WordRepository wordRepository = new WordRepository(databaseManager);
        SettingsService settingsService = new SettingsService(new SettingsRepository(databaseManager));
        DeckService deckService = new DeckService(deckRepository, settingsService);
        Deck deck = deckService.resolveStartupDeck();
        StarterImportService starterImportService = new StarterImportService(
            new ImportExportService(wordRepository, new WordValidationService()),
            wordRepository,
            new ReviewLogRepository(databaseManager),
            new GoalRepository(databaseManager),
            settingsService
        );
        Optional<ImportResult> starter = starterImportService.importOnce(deck.getId());
        return new Launch(deck, starter, deckService, settingsService, deckRepository, wordRepository);
    }

    private record Launch(Deck deck, Optional<ImportResult> starter, DeckService deckService,
                          SettingsService settings, DeckRepository decks, WordRepository words) {
    }
}
