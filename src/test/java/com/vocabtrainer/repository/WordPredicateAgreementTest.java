package com.vocabtrainer.repository;

import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.ReviewLog;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
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
 * every combination of the fields involved, each also as a suspended word, so the two cannot drift
 * apart unnoticed and no query counts a suspended word.
 */
class WordPredicateAgreementTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 10, 12, 0);
    /** The next 4 am rollover after NOW. */
    private static final LocalDateTime DAY_END = LocalDateTime.of(2026, 3, 11, 4, 0);

    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    private DatabaseManager databaseManager;
    private WordRepository words;
    private DeckRepository decks;
    private Deck deck;
    /** Every word of the deck, suspended ones included. */
    private List<WordCard> all;
    private List<WordCard> active;
    private Set<Long> suspended;

    @BeforeEach
    void insertEveryCombination() throws SQLException {
        databaseManager = databases.open(tempDir.resolve("test.db"));
        decks = new DeckRepository(databaseManager);
        words = new WordRepository(databaseManager);
        deck = decks.create("Predicates");
        List<WordCard> cards = new ArrayList<>();
        int index = 0;
        int[][] histories = {{0, 0}, {3, 3}, {3, 2}, {5, 2}, {5, 3}, {4, 0}};
        LocalDateTime[] dueTimes = {NOW.minusDays(1), NOW, NOW.plusSeconds(1), DAY_END.minusSeconds(1), DAY_END};
        for (CardState state : CardState.values()) {
            for (double stability : new double[] {0, 20.9, 21, 30}) {
                for (double difficulty : new double[] {0, 6.9, 7, 9}) {
                    for (int[] history : histories) {
                        for (LocalDateTime next : dueTimes) {
                            for (boolean isSuspended : new boolean[] {false, true}) {
                                WordCard card = WordCard.createNew(deck.getId(), "word" + index++, "词");
                                card.setState(state);
                                card.setStability(stability);
                                card.setDifficulty(difficulty);
                                card.setRepetitions(history[0]);
                                card.setConsecutiveCorrect(history[1]);
                                card.setNextReviewAt(next);
                                card.setSuspended(isSuspended);
                                cards.add(card);
                            }
                        }
                    }
                }
            }
        }
        words.insertAll(cards);
        all = words.findAllIncludingSuspended(deck.getId());
        active = words.findAll(deck.getId());
        suspended = all.stream().filter(WordCard::isSuspended).map(WordCard::getId).collect(Collectors.toSet());
        assertEquals(cards.size(), all.size());
        assertEquals(cards.size() / 2, active.size());
        assertEquals(cards.size() / 2, suspended.size());
        assertTrue(active.stream().noneMatch(WordCard::isSuspended));
    }

    @Test
    void suspendedWordsAreNeverDueWeakOrMasteredWhateverTheirFields() {
        assertTrue(all.stream().filter(WordCard::isSuspended)
            .noneMatch(card -> card.isDue(NOW, DAY_END) || card.isWeak() || card.isMastered()));
    }

    @Test
    void theWordListAndDuplicateChecksIncludeSuspendedWords() throws SQLException {
        assertEquals(ids(all.stream()), ids(words.search(deck.getId(), "").stream()));
        assertEquals(ids(all.stream()), ids(words.search(deck.getId(), "word").stream()));
        WordCard aSuspendedWord = all.stream().filter(WordCard::isSuspended).findFirst().orElseThrow();
        assertEquals(aSuspendedWord.getId(),
            words.findByEnglish(deck.getId(), aSuspendedWord.getEnglish().toUpperCase()).orElseThrow().getId());
        assertTrue(words.findEnglishKeys(deck.getId()).contains(aSuspendedWord.getEnglish()));
        assertEquals(suspended.size(), words.countSuspended(deck.getId()));
        assertEquals(all.size(), words.countAll(deck.getId()));
    }

    @Test
    void weakWordsModeSelectsExactlyTheWordsWordCardCallsWeak() throws SQLException {
        Set<Long> sql = words.findWeak(deck.getId(), 10_000).stream().map(WordCard::getId).collect(Collectors.toSet());
        Set<Long> java = all.stream().filter(WordCard::isWeak).map(WordCard::getId).collect(Collectors.toSet());

        assertEquals(java, sql);
        assertTrue(java.stream().noneMatch(suspended::contains));
        assertFalse(java.isEmpty());
        assertTrue(java.size() < active.size(), "the cases must include words that are not weak");
    }

    @Test
    void theMasteredCountMatchesWordCard() throws SQLException {
        long java = all.stream().filter(WordCard::isMastered).count();

        assertEquals(java, words.countMastered(deck.getId()));
        assertTrue(java > 0);
    }

    @Test
    void dueQueriesMatchWordCard() throws SQLException {
        Set<Long> java = all.stream().filter(card -> card.isDue(NOW, DAY_END)).map(WordCard::getId)
            .collect(Collectors.toSet());

        assertEquals(java.size(), words.countDue(deck.getId(), NOW, DAY_END));
        assertTrue(java.stream().noneMatch(suspended::contains));
        assertTrue(java.size() < active.size(), "the cases must include words that are not due");
        // Later today: due for a review card, not yet for a learning card.
        assertTrue(active.stream().anyMatch(card -> card.getState() == CardState.REVIEW
            && card.getNextReviewAt().equals(DAY_END.minusSeconds(1)) && java.contains(card.getId())));
        assertTrue(active.stream().noneMatch(card -> card.getState().isLearning()
            && card.getNextReviewAt().isAfter(NOW) && java.contains(card.getId())));
    }

    @Test
    void theReviewQueuesSplitTheDueWordsByState() throws SQLException {
        List<WordCard> due = all.stream().filter(card -> card.isDue(NOW, DAY_END)).toList();
        Set<Long> reviews = ids(due.stream().filter(card -> card.getState() == CardState.REVIEW));
        Set<Long> newCards = ids(due.stream().filter(card -> card.getState() == CardState.NEW));
        Set<Long> learning = ids(due.stream().filter(card -> card.getState().isLearning()));

        assertEquals(new WordRepository.DueCounts(learning.size(), reviews.size(), newCards.size()),
            words.countDueByState(deck.getId(), NOW, DAY_END));
        assertEquals(due.size(), learning.size() + reviews.size() + newCards.size());
        assertEquals(reviews, ids(words.findDueReviews(deck.getId(), NOW, DAY_END, 10_000).stream()));
        assertEquals(newCards, ids(words.findNewCards(deck.getId(), DAY_END, 10_000).stream()));
        assertEquals(learning, ids(words.findLearningDueBy(deck.getId(), NOW, 10_000).stream()));
        assertFalse(reviews.isEmpty() || newCards.isEmpty() || learning.isEmpty());
    }

    @Test
    void dueReviewsComeLowestRetrievabilityFirstAndNewCardsInTheOrderTheyWereAdded() throws SQLException {
        Deck queue = decks.create("Queue");
        // Due reviews last reviewed 10 days ago at a stability of 30 days, 5 at 2, 20 at 10, and one never timed.
        WordCard fresh = words.insert(review(queue, "fresh", 30, NOW.minusDays(10)));
        WordCard forgotten = words.insert(review(queue, "forgotten", 2, NOW.minusDays(5)));
        WordCard overdue = words.insert(review(queue, "overdue", 10, NOW.minusDays(20)));
        WordCard untimed = words.insert(review(queue, "untimed", 5, null));
        WordCard later = WordCard.createNew(queue.getId(), "later", "词");
        later.setAddedAt(NOW.minusDays(1));
        later.setNextReviewAt(NOW.minusDays(1));
        WordCard earlier = WordCard.createNew(queue.getId(), "earlier", "词");
        earlier.setAddedAt(NOW.minusDays(2));
        earlier.setNextReviewAt(NOW.minusDays(2));
        WordCard sameTime = WordCard.createNew(queue.getId(), "same time", "词");
        sameTime.setAddedAt(NOW.minusDays(1));
        sameTime.setNextReviewAt(NOW.minusDays(1));
        words.insert(later);
        words.insert(earlier);
        words.insert(sameTime);

        // Elapsed time over stability, highest (lowest recall) first: 2.5, 2, 1 (no last review: as if just due), 1/3.
        assertEquals(List.of(forgotten.getId(), overdue.getId(), untimed.getId(), fresh.getId()),
            words.findDueReviews(queue.getId(), NOW, DAY_END, 10).stream().map(WordCard::getId).toList());
        assertEquals(List.of(earlier.getId(), later.getId(), sameTime.getId()),
            words.findNewCards(queue.getId(), DAY_END, 10).stream().map(WordCard::getId).toList());
        assertEquals(1, words.findNewCards(queue.getId(), DAY_END, 1).size());
    }

    private static WordCard review(Deck deck, String english, double stability, LocalDateTime lastReviewedAt) {
        WordCard card = WordCard.createNew(deck.getId(), english, "词");
        card.setState(CardState.REVIEW);
        card.setStability(stability);
        card.setDifficulty(5);
        card.setRepetitions(3);
        card.setConsecutiveCorrect(3);
        card.setLastReviewedAt(lastReviewedAt);
        // Due today in any case, e.g. after the desired retention was raised.
        card.setNextReviewAt(NOW.minusHours(1));
        return card;
    }

    private static Set<Long> ids(java.util.stream.Stream<WordCard> cards) {
        return cards.map(WordCard::getId).collect(Collectors.toSet());
    }

    @Test
    void learningCardsDueByATimeAreFoundSoonestFirst() throws SQLException {
        LocalDateTime until = NOW.plusMinutes(20);
        List<Long> java = active.stream()
            .filter(card -> card.getState().isLearning() && !card.getNextReviewAt().isAfter(until))
            .sorted(Comparator.comparing(WordCard::getNextReviewAt).thenComparingLong(WordCard::getId))
            .map(WordCard::getId)
            .toList();

        assertEquals(java, words.findLearningDueBy(deck.getId(), until, 10_000).stream().map(WordCard::getId).toList());
        assertFalse(java.isEmpty());
    }

    @Test
    void perDeckCountsInOneQueryMatchTheSingleDeckCounts() throws SQLException {
        Deck other = decks.create("Other");
        words.insert(WordCard.createNew(other.getId(), "lone", "孤独的"));
        Deck empty = decks.create("Empty");

        Map<Long, WordRepository.DeckWordCounts> counts = words.countByDeck(NOW, DAY_END);

        for (Deck each : List.of(deck, other)) {
            assertEquals(new WordRepository.DeckWordCounts(words.countAll(each.getId()),
                words.countDue(each.getId(), NOW, DAY_END), words.countDueByState(each.getId(), NOW, DAY_END).newCards()),
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
