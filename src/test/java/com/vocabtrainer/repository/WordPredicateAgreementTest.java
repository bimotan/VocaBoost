package com.vocabtrainer.repository;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.ReviewLog;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The weak, mastered and due rules exist twice: as WordCard predicates (Word List filters, Status
 * column) and as SQL (weak-words review mode, dashboard and deck counts). These tests run both over
 * every combination of the fields involved, so the two cannot drift apart unnoticed.
 */
class WordPredicateAgreementTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 10, 12, 0);

    @TempDir
    Path tempDir;

    private DatabaseManager databaseManager;
    private WordRepository words;
    private DeckRepository decks;
    private Deck deck;
    private List<WordCard> active;

    @BeforeEach
    void insertEveryCombination() throws SQLException {
        databaseManager = new DatabaseManager(tempDir.resolve("test.db"));
        databaseManager.initialize();
        decks = new DeckRepository(databaseManager);
        words = new WordRepository(databaseManager);
        deck = decks.create("Predicates");
        List<WordCard> cards = new ArrayList<>();
        int index = 0;
        for (int lapses : new int[] {0, 1, 2}) {
            for (int consecutive : new int[] {0, 2, 3, 4}) {
                for (int interval : new int[] {0, 3, 4, 6, 7, 8}) {
                    for (LocalDateTime next : new LocalDateTime[] {NOW.minusDays(1), NOW, NOW.plusSeconds(1)}) {
                        WordCard card = WordCard.createNew(deck.getId(), "word" + index++, "词");
                        card.setLapses(lapses);
                        card.setConsecutiveCorrect(consecutive);
                        card.setIntervalDays(interval);
                        card.setRepetitions(consecutive);
                        card.setNextReviewAt(next);
                        cards.add(card);
                    }
                }
            }
        }
        // Archived words count nowhere, whatever their fields say.
        WordCard archived = WordCard.createNew(deck.getId(), "archived", "词");
        archived.setArchived(true);
        archived.setNextReviewAt(NOW.minusDays(1));
        cards.add(archived);
        words.insertAll(cards);
        active = words.findAll(deck.getId());
        assertEquals(cards.size() - 1, active.size());
    }

    @Test
    void weakWordsModeSelectsExactlyTheWordsWordCardCallsWeak() throws SQLException {
        Set<Long> sql = words.findWeak(deck.getId(), 10_000).stream().map(WordCard::getId).collect(Collectors.toSet());
        Set<Long> java = active.stream().filter(WordCard::isWeak).map(WordCard::getId).collect(Collectors.toSet());

        assertEquals(java, sql);
        assertFalse(java.isEmpty());
        assertTrue(java.size() < active.size(), "the cases must include words that are not weak");
    }

    @Test
    void theMasteredCountMatchesWordCard() throws SQLException {
        long java = active.stream().filter(WordCard::isMastered).count();

        assertEquals(java, words.countMastered(deck.getId()));
        assertTrue(java > 0);
    }

    @Test
    void dueQueriesMatchWordCard() throws SQLException {
        Set<Long> java = active.stream().filter(card -> card.isDue(NOW)).map(WordCard::getId).collect(Collectors.toSet());

        assertEquals(java.size(), words.countDue(deck.getId(), NOW));
        assertEquals(java, words.findDue(deck.getId(), NOW, 10_000).stream().map(WordCard::getId)
            .collect(Collectors.toSet()));
        assertTrue(java.size() < active.size(), "the cases must include words that are not due");
    }

    @Test
    void perDeckCountsInOneQueryMatchTheSingleDeckCounts() throws SQLException {
        Deck other = decks.create("Other");
        words.insert(WordCard.createNew(other.getId(), "lone", "孤独的"));
        Deck empty = decks.create("Empty");

        Map<Long, WordRepository.DeckWordCounts> counts = words.countByDeck(NOW);

        for (Deck each : List.of(deck, other)) {
            assertEquals(new WordRepository.DeckWordCounts(words.countAll(each.getId()), words.countDue(each.getId(), NOW)),
                counts.get(each.getId()), each.getName());
        }
        assertFalse(counts.containsKey(empty.getId()));
    }

    @Test
    void theLatestReviewPerDeckIsTheNewestLogOfItsWords() throws SQLException {
        ReviewLogRepository logs = new ReviewLogRepository(databaseManager);
        Deck other = decks.create("Other");
        WordCard otherWord = words.insert(WordCard.createNew(other.getId(), "lone", "孤独的"));
        logs.insert(log(active.get(0), NOW.minusDays(2)));
        logs.insert(log(active.get(1), NOW.minusHours(1)));
        logs.insert(log(otherWord, NOW.minusDays(5)));

        Map<Long, LocalDateTime> latest = logs.latestReviewByDeck();

        assertEquals(Map.of(deck.getId(), NOW.minusHours(1), other.getId(), NOW.minusDays(5)), latest);
    }

    private static ReviewLog log(WordCard word, LocalDateTime at) {
        return new ReviewLog(0, word.getId(), at, "词", "词", 1.0, ReviewRating.GOOD, 1000);
    }
}
