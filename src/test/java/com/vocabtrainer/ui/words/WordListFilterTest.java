package com.vocabtrainer.ui.words;

import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.WordCard;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WordListFilterTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 10, 12, 0);
    /** The next 4 am rollover: words due before it are due today. */
    private static final LocalDateTime DAY_END = LocalDateTime.of(2026, 3, 11, 4, 0);

    private final WordCard fresh = word("abate", "verb", "gre", CardState.NEW, 0, 0, 0, 0, 0, NOW.minusHours(1));
    private final WordCard mastered = word("petrichor", "noun", "mine", CardState.REVIEW, 30, 5, 4, 4, 0, NOW.plusDays(10));
    private final WordCard lapsedLater = word("laud", "verb", "gre; UNVERIFIED", CardState.REVIEW, 5, 5, 4, 1, 1,
        NOW.plusDays(2));
    private final WordCard learning = word("lucid", "adjective", "Mine", CardState.LEARNING, 3, 5.3, 1, 1, 0,
        NOW.plusMinutes(10));
    private final WordCard leech = word("cavil", "noun", "gre; leech", CardState.REVIEW, 2, 9, 20, 3, 8,
        NOW.plusDays(1));
    private final List<WordCard> words = List.of(fresh, mastered, lapsedLater, learning, leech);

    @Test
    void statusFiltersUseTheWordCardRules() {
        assertEquals(words, matching(new WordListFilter("All", "", "")));
        assertEquals(words, matching(new WordListFilter(null, null, null)));
        assertEquals(List.of(fresh), matching(new WordListFilter("Due", "", "")));
        assertEquals(List.of(lapsedLater, leech), matching(new WordListFilter("Weak", "", "")));
        assertEquals(List.of(mastered), matching(new WordListFilter("Mastered", "", "")));
        assertEquals(List.of(leech), matching(new WordListFilter("Leech", "", "")));
        assertEquals(List.of(lapsedLater), matching(new WordListFilter("Unverified", "", "")));
    }

    @Test
    void tagAndPartOfSpeechMatchPartsIgnoringCase() {
        assertEquals(List.of(mastered, learning), matching(new WordListFilter("All", " MINE ", "")));
        assertEquals(List.of(fresh, lapsedLater), matching(new WordListFilter("All", "", "VERB")));
        assertEquals(List.of(learning), matching(new WordListFilter("All", "", "adj")));
        assertEquals(List.of(lapsedLater), matching(new WordListFilter("Weak", "gre", "verb")));
        assertEquals(List.of(), matching(new WordListFilter("All", "no-such-tag", "")));
    }

    @Test
    void theStatusColumnSaysMasteredDueNewOrLearning() {
        assertEquals("Mastered", WordListFilter.statusOf(mastered, NOW, DAY_END));
        assertEquals("Due", WordListFilter.statusOf(fresh, NOW, DAY_END));
        assertEquals("Learning", WordListFilter.statusOf(learning, NOW, DAY_END));
        WordCard notYetDue = word("new", "noun", "", CardState.NEW, 0, 0, 0, 0, 0, DAY_END);
        assertEquals("New", WordListFilter.statusOf(notYetDue, NOW, DAY_END));
        // The clock decides, not the time the test runs.
        assertEquals("Due", WordListFilter.statusOf(learning, NOW.plusMinutes(10), DAY_END));
    }

    @Test
    void aReviewWordIsDueAllDayButALearningWordOnlyFromItsStepTime() {
        WordCard review = word("review", "noun", "", CardState.REVIEW, 3, 5, 2, 2, 0, NOW.plusHours(3));
        WordCard step = word("step", "noun", "", CardState.LEARNING, 3, 5, 1, 1, 0, NOW.plusHours(3));

        assertEquals("Due", WordListFilter.statusOf(review, NOW, DAY_END));
        assertEquals("Learning", WordListFilter.statusOf(step, NOW, DAY_END));
    }

    private List<WordCard> matching(WordListFilter filter) {
        return words.stream().filter(word -> filter.matches(word, NOW, DAY_END)).toList();
    }

    private static WordCard word(String english, String pos, String tags, CardState state, double stability,
                                 double difficulty, int repetitions, int consecutiveCorrect, int lapses,
                                 LocalDateTime nextReviewAt) {
        WordCard word = WordCard.createNew(1, english, "词");
        word.setPartOfSpeech(pos);
        word.setTags(tags);
        word.setState(state);
        word.setStability(stability);
        word.setDifficulty(difficulty);
        word.setRepetitions(repetitions);
        word.setConsecutiveCorrect(consecutiveCorrect);
        word.setLapses(lapses);
        word.setNextReviewAt(nextReviewAt);
        return word;
    }
}
