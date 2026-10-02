package com.vocabtrainer.ui.words;

import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.WordCard;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * The Word List's status, tag and part-of-speech filters and its Status column, without JavaFX.
 * The rules for weak and mastered words come from {@link WordCard}, so the list agrees with the
 * weak-words review mode and the dashboard.
 *
 * @param status       one of {@link #STATUSES}; null means All
 * @param tag          part of a tag, ignoring case; blank matches every word
 * @param partOfSpeech part of the part of speech, ignoring case; blank matches every word
 */
public record WordListFilter(String status, String tag, String partOfSpeech) {
    public static final List<String> STATUSES = List.of("All", "Due", "Weak", "Mastered", "Leech", "Unverified");

    /** @param dayEnd the end of the current study day, which decides which words are due today */
    public boolean matches(WordCard word, LocalDateTime now, LocalDateTime dayEnd) {
        if ("Due".equals(status) && !word.isDue(now, dayEnd)) {
            return false;
        }
        if ("Leech".equals(status) && !word.isLeech()) {
            return false;
        }
        if ("Weak".equals(status) && !word.isWeak()) {
            return false;
        }
        if ("Mastered".equals(status) && !word.isMastered()) {
            return false;
        }
        if ("Unverified".equals(status) && !containsIgnoreCase(word.getTags(), "UNVERIFIED")) {
            return false;
        }
        if (tag != null && !tag.isBlank() && !containsIgnoreCase(word.getTags(), tag.trim())) {
            return false;
        }
        return partOfSpeech == null || partOfSpeech.isBlank()
            || containsIgnoreCase(word.getPartOfSpeech(), partOfSpeech.trim());
    }

    /** The Status column: Mastered, Due, New or Learning. */
    public static String statusOf(WordCard word, LocalDateTime now, LocalDateTime dayEnd) {
        if (word.isMastered()) {
            return "Mastered";
        }
        if (word.isDue(now, dayEnd)) {
            return "Due";
        }
        if (word.getState() == CardState.NEW) {
            return "New";
        }
        return "Learning";
    }

    private static boolean containsIgnoreCase(String value, String needle) {
        return value != null && needle != null
            && value.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }
}
