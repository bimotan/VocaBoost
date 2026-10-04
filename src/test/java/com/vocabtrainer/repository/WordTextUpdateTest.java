package com.vocabtrainer.repository;

import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.ValidatedWord;
import com.vocabtrainer.domain.WordCard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Saving a word's text from the edit dialog writes the text columns and nothing else. */
class WordTextUpdateTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 5, 28, 9, 0);

    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    @Test
    void updateTextWritesTheTextAndLeavesTheScheduleAndDeck() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("text.db"));
        Deck deck = new DeckRepository(databaseManager).ensureDefaultDeck();
        WordRepository words = new WordRepository(databaseManager);
        WordCard word = WordCard.createNew(deck.getId(), "querulous", "抱怨的", NOW);
        word.setState(CardState.REVIEW);
        word.setStability(21.5);
        word.setDifficulty(6.25);
        word.setRepetitions(5);
        word.setLapses(1);
        word.setLastReviewedAt(NOW);
        word.setNextReviewAt(NOW.plusDays(21));
        words.insert(word);

        assertTrue(words.updateText(word.getId(), new ValidatedWord(" Querulous ", "爱抱怨的", "/ˈkwer.ə.ləs/",
            "adjective", "A querulous voice.", " ", "mine")));

        WordCard saved = words.findById(word.getId()).orElseThrow();
        assertEquals("Querulous", saved.getEnglish());
        assertEquals("爱抱怨的", saved.getChinese());
        assertEquals("/ˈkwer.ə.ləs/", saved.getPhonetic());
        assertEquals("adjective", saved.getPartOfSpeech());
        assertEquals("A querulous voice.", saved.getExampleSentence());
        assertNull(saved.getNote(), "blank text is stored as no value, as save() does");
        assertEquals("mine", saved.getTags());
        assertEquals(deck.getId(), saved.getDeckId());
        assertEquals(CardState.REVIEW, saved.getState());
        assertEquals(21.5, saved.getStability());
        assertEquals(6.25, saved.getDifficulty());
        assertEquals(5, saved.getRepetitions());
        assertEquals(1, saved.getLapses());
        assertEquals(NOW, saved.getLastReviewedAt());
        assertEquals(NOW.plusDays(21), saved.getNextReviewAt());

        assertFalse(words.updateText(word.getId() + 1, new ValidatedWord("x", "x", null, null, null, null, null)),
            "no such word");
    }
}
