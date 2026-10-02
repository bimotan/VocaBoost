package com.vocabtrainer.ui.words;

import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.WordCard;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    private final WordCard suspendedLeech = suspended(word("carp", "verb", "gre; leech", CardState.RELEARNING, 1, 9,
        20, 0, 9, NOW.minusHours(1)));
    private final List<WordCard> words = List.of(fresh, mastered, lapsedLater, learning, leech, suspendedLeech);

    @Test
    void statusFiltersUseTheWordCardRules() {
        assertEquals(words, matching(new WordListFilter("All", "", "")));
        assertEquals(words, matching(new WordListFilter(null, null, null)));
        assertEquals(List.of(fresh), matching(new WordListFilter("Due", "", "")));
        assertEquals(List.of(lapsedLater, leech), matching(new WordListFilter("Weak", "", "")));
        assertEquals(List.of(mastered), matching(new WordListFilter("Mastered", "", "")));
        assertEquals(List.of(leech, suspendedLeech), matching(new WordListFilter("Leech", "", "")));
        assertEquals(List.of(suspendedLeech), matching(new WordListFilter("Suspended", "", "")));
        assertEquals(List.of(lapsedLater), matching(new WordListFilter("Unverified", "", "")));
    }

    @Test
    void aSuspendedWordSaysSoAndIsNeverDueWeakOrMastered() {
        assertEquals("Suspended", WordListFilter.statusOf(suspendedLeech, NOW, DAY_END));
        assertEquals("Suspended", WordListFilter.statusOf(suspended(word("zeal", "noun", "", CardState.REVIEW, 40, 3,
            5, 5, 0, NOW.plusDays(30))), NOW, DAY_END));
        assertEquals(List.of(suspendedLeech), matching(new WordListFilter("All", "", "verb")).stream()
            .filter(WordCard::isSuspended).toList());
    }

    @Test
    void tagAndPartOfSpeechMatchPartsIgnoringCase() {
        assertEquals(List.of(mastered, learning), matching(new WordListFilter("All", " MINE ", "")));
        assertEquals(List.of(fresh, lapsedLater, suspendedLeech), matching(new WordListFilter("All", "", "VERB")));
        assertEquals(List.of(learning), matching(new WordListFilter("All", "", "adj")));
        assertEquals(List.of(lapsedLater), matching(new WordListFilter("Weak", "gre", "verb")));
        assertEquals(List.of(), matching(new WordListFilter("All", "no-such-tag", "")));
    }

    @Test
    void theSearchBoxMatchesPartsOfEnglishChineseOrTagsIgnoringCase() {
        mastered.setChinese("雨后泥土的气味");
        assertEquals(words, words.stream().filter(WordListFilter.searching("  ")).toList());
        assertEquals(words, words.stream().filter(WordListFilter.searching(null)).toList());
        assertEquals(List.of(fresh), words.stream().filter(WordListFilter.searching(" ABAT ")).toList());
        assertEquals(List.of(mastered), words.stream().filter(WordListFilter.searching("泥土")).toList());
        assertEquals(List.of(lapsedLater), words.stream().filter(WordListFilter.searching("unverified")).toList());
        assertEquals(List.of(), words.stream().filter(WordListFilter.searching("%")).toList(),
            "no SQL wildcards");
    }

    @Test
    void searchingTenThousandWordsIsFast() {
        List<WordCard> many = new ArrayList<>();
        for (int index = 0; index < 10_000; index++) {
            many.add(word(String.format("word%05d", index), "noun", "generated", CardState.NEW, 0, 0, 0, 0, 0, NOW));
        }
        WordListFilter filter = new WordListFilter("Due", "gen", "NOUN");
        long start = System.nanoTime();
        int matches = 0;
        // Ten keystrokes' worth of filtering, each over every word.
        for (String query : List.of("w", "wo", "wor", "word", "word0", "word09", "word099", "word0999", "x", "")) {
            var search = WordListFilter.searching(query);
            matches = (int) many.stream().filter(word -> search.test(word) && filter.matches(word, NOW, DAY_END)).count();
        }
        long millis = (System.nanoTime() - start) / 1_000_000L;
        assertEquals(10_000, matches);
        // Generous: it takes a few milliseconds; a database query per keystroke took far longer.
        assertTrue(millis < 2_000, "filtering took " + millis + " ms");
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

    private static WordCard suspended(WordCard word) {
        word.setSuspended(true);
        return word;
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
