package com.vocabtrainer.service;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.DeckRepository;
import com.vocabtrainer.repository.SettingsRepository;
import com.vocabtrainer.repository.TestDatabases;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeckServiceTest {
    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    @Test
    void createsRenamesAndArchivesDecks() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("decks.db"));
        DeckRepository deckRepository = new DeckRepository(databaseManager);
        DeckService deckService = new DeckService(deckRepository, new SettingsService(new SettingsRepository(databaseManager)));

        Deck defaultDeck = deckService.ensureDefaultDeck();
        Deck greDeck = deckService.createDeck("GRE 高频");

        assertEquals(2, deckService.activeDecks().size());
        Deck renamed = deckService.renameDeck(greDeck.getId(), "GRE 核心词");
        assertEquals("GRE 核心词", renamed.getName());

        Deck fallback = deckService.archiveDeck(renamed.getId());
        List<Deck> activeDecks = deckService.activeDecks();
        assertEquals(1, activeDecks.size());
        assertEquals(defaultDeck.getId(), fallback.getId());
        assertFalse(deckRepository.findByName("GRE 核心词").isPresent());
        assertTrue(deckRepository.findById(renamed.getId()).orElseThrow().isArchived());

        assertEquals(1, deckService.archivedDecks().size());
        Deck restored = deckService.restoreDeck(renamed.getId());
        assertEquals("GRE 核心词", restored.getName());
        assertFalse(restored.isArchived());
        assertEquals(2, deckService.activeDecks().size());
        assertTrue(deckService.archivedDecks().isEmpty());
    }

    @Test
    void doesNotArchiveLastActiveDeckAndValidatesNames() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("validation.db"));
        DeckService deckService = new DeckService(new DeckRepository(databaseManager),
            new SettingsService(new SettingsRepository(databaseManager)));

        Deck defaultDeck = deckService.ensureDefaultDeck();

        assertThrows(IllegalArgumentException.class, () -> deckService.createDeck("   "));
        assertThrows(IllegalArgumentException.class, () -> deckService.archiveDeck(defaultDeck.getId()));
    }

    @Test
    void keepsOneActiveDeckAfterTheDefaultDeckIsArchived() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("last-active.db"));
        DeckService deckService = new DeckService(new DeckRepository(databaseManager),
            new SettingsService(new SettingsRepository(databaseManager)));
        Deck defaultDeck = deckService.ensureDefaultDeck();
        Deck greDeck = deckService.createDeck("GRE");

        assertEquals(greDeck.getId(), deckService.archiveDeck(defaultDeck.getId()).getId());

        IllegalArgumentException lastActive = assertThrows(IllegalArgumentException.class,
            () -> deckService.archiveDeck(greDeck.getId()));
        assertTrue(lastActive.getMessage().contains("至少需要保留一个活动词库"), lastActive.getMessage());
        assertNull(lastActive.getCause(), "shown to the user as a plain validation message");
        assertThrows(IllegalArgumentException.class, () -> deckService.archiveDeck(defaultDeck.getId()));
        assertEquals(List.of(greDeck.getId()), deckService.activeDecks().stream().map(Deck::getId).toList());
    }

    @Test
    void ensureDefaultDeckRestoresArchivedDefaultDeckInsteadOfInsertingTheNameAgain() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("archived-default.db"));
        DeckRepository deckRepository = new DeckRepository(databaseManager);
        Deck defaultDeck = deckRepository.ensureDefaultDeck();
        deckRepository.create("GRE");
        deckRepository.archive(defaultDeck.getId());

        Deck ensured = deckRepository.ensureDefaultDeck();

        assertEquals(defaultDeck.getId(), ensured.getId());
        assertFalse(ensured.isArchived());
        assertEquals(2, deckRepository.findAllIncludingArchived().size());
    }

    @Test
    void explainsNameConflictsWithArchivedDecks() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("names.db"));
        DeckService deckService = new DeckService(new DeckRepository(databaseManager),
            new SettingsService(new SettingsRepository(databaseManager)));
        Deck defaultDeck = deckService.ensureDefaultDeck();
        Deck greDeck = deckService.createDeck("GRE");
        deckService.archiveDeck(greDeck.getId());

        IllegalArgumentException create = assertThrows(IllegalArgumentException.class, () -> deckService.createDeck("GRE"));
        assertTrue(create.getMessage().contains("已归档"), create.getMessage());
        assertNull(create.getCause());
        IllegalArgumentException rename = assertThrows(IllegalArgumentException.class,
            () -> deckService.renameDeck(defaultDeck.getId(), "GRE"));
        assertTrue(rename.getMessage().contains("已归档"), rename.getMessage());
        IllegalArgumentException active = assertThrows(IllegalArgumentException.class,
            () -> deckService.createDeck(DeckRepository.DEFAULT_DECK_NAME));
        assertTrue(active.getMessage().contains("已有同名词库"), active.getMessage());

        assertEquals(DeckRepository.DEFAULT_DECK_NAME,
            deckService.renameDeck(defaultDeck.getId(), DeckRepository.DEFAULT_DECK_NAME).getName());
    }
}
