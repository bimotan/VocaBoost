package com.vocabtrainer.ui.words;

import com.vocabtrainer.domain.WordCard;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WordListFilterTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 10, 12, 0);

    private final WordCard fresh = word("abate", "verb", "gre", 0, 0, 0, NOW.minusHours(1));
    private final WordCard mastered = word("petrichor", "noun", "mine", 4, 10, 0, NOW.plusDays(10));
    private final WordCard lapsedLater = word("laud", "verb", "gre; UNVERIFIED", 3, 8, 1, NOW.plusDays(2));
    private final WordCard learning = word("lucid", "adjective", "Mine", 1, 1, 0, NOW.plusDays(1));
    private final List<WordCard> words = List.of(fresh, mastered, lapsedLater, learning);

    @Test
    void statusFiltersUseTheWordCardRules() {
        assertEquals(words, matching(new WordListFilter("All", "", "")));
        assertEquals(words, matching(new WordListFilter(null, null, null)));
        assertEquals(List.of(fresh), matching(new WordListFilter("Due", "", "")));
        assertEquals(List.of(fresh, lapsedLater, learning), matching(new WordListFilter("Weak", "", "")));
        assertEquals(List.of(mastered), matching(new WordListFilter("Mastered", "", "")));
        assertEquals(List.of(lapsedLater), matching(new WordListFilter("Unverified", "", "")));
    }

    @Test
    void tagAndPartOfSpeechMatchPartsIgnoringCase() {
        assertEquals(List.of(mastered, learning), matching(new WordListFilter("All", " MINE ", "")));
        assertEquals(List.of(fresh, lapsedLater), matching(new WordListFilter("All", "", "VERB")));
        assertEquals(List.of(learning), matching(new WordListFilter("All", "", "adj")));
        assertEquals(List.of(fresh, lapsedLater), matching(new WordListFilter("Weak", "gre", "verb")));
        assertEquals(List.of(), matching(new WordListFilter("All", "no-such-tag", "")));
    }

    @Test
    void theStatusColumnSaysMasteredDueNewOrLearning() {
        assertEquals("Mastered", WordListFilter.statusOf(mastered, NOW));
        assertEquals("Due", WordListFilter.statusOf(fresh, NOW));
        assertEquals("Learning", WordListFilter.statusOf(learning, NOW));
        WordCard notYetDue = word("new", "noun", "", 0, 0, 0, NOW.plusMinutes(1));
        assertEquals("New", WordListFilter.statusOf(notYetDue, NOW));
        // The clock decides, not the time the test runs.
        assertEquals("Due", WordListFilter.statusOf(learning, NOW.plusDays(1)));
    }

    private List<WordCard> matching(WordListFilter filter) {
        return words.stream().filter(word -> filter.matches(word, NOW)).toList();
    }

    private static WordCard word(String english, String pos, String tags, int consecutiveCorrect, int intervalDays,
                                 int lapses, LocalDateTime nextReviewAt) {
        WordCard word = WordCard.createNew(1, english, "词");
        word.setPartOfSpeech(pos);
        word.setTags(tags);
        word.setConsecutiveCorrect(consecutiveCorrect);
        word.setRepetitions(consecutiveCorrect);
        word.setIntervalDays(intervalDays);
        word.setLapses(lapses);
        word.setNextReviewAt(nextReviewAt);
        return word;
    }
}
