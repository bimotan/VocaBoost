package com.vocabtrainer.service;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.DeckRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeckServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void createsRenamesAndArchivesDecks() throws Exception {
        DatabaseManager databaseManager = new DatabaseManager(tempDir.resolve("decks.db"));
        databaseManager.initialize();
        DeckRepository deckRepository = new DeckRepository(databaseManager);
        DeckService deckService = new DeckService(deckRepository);

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
        DatabaseManager databaseManager = new DatabaseManager(tempDir.resolve("validation.db"));
        databaseManager.initialize();
        DeckService deckService = new DeckService(new DeckRepository(databaseManager));

        Deck defaultDeck = deckService.ensureDefaultDeck();

        assertThrows(IllegalArgumentException.class, () -> deckService.createDeck("   "));
        assertThrows(IllegalArgumentException.class, () -> deckService.archiveDeck(defaultDeck.getId()));
    }
}
