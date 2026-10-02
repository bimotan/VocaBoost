package com.vocabtrainer.repository;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.WordCard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Finding the word being added in the other decks. */
class CrossDeckWordsTest {
    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    @Test
    void findsTheWordInTheOtherActiveDecksIgnoringCaseOldestDeckFirst() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("decks.db"));
        DeckRepository decks = new DeckRepository(databaseManager);
        WordRepository words = new WordRepository(databaseManager);
        Deck starter = decks.ensureDefaultDeck();
        Deck gre = decks.create("GRE 3000");
        Deck notebook = decks.create("Error notebook");
        Deck archived = decks.create("Old list");
        words.insert(WordCard.createNew(starter.getId(), "abate", "减弱; 减少"));
        words.insert(WordCard.createNew(gre.getId(), "Abate", "减轻"));
        words.insert(WordCard.createNew(gre.getId(), "abase", "贬低"));
        words.insert(WordCard.createNew(archived.getId(), "abate", "减退"));
        decks.archive(archived.getId());

        List<WordCard> elsewhere = words.findInOtherDecks(" ABATE ", notebook.getId());
        assertEquals(List.of(starter.getId(), gre.getId()), elsewhere.stream().map(WordCard::getDeckId).toList(),
            "active decks only, oldest first");
        assertEquals(List.of("减弱; 减少", "减轻"), elsewhere.stream().map(WordCard::getChinese).toList());

        assertEquals(List.of(gre.getId()), words.findInOtherDecks("abate", starter.getId()).stream()
            .map(WordCard::getDeckId).toList(), "not the deck the word is added to");
        assertEquals(List.of(), words.findInOtherDecks("abhor", notebook.getId()));
    }
}
