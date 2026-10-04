package com.vocabtrainer.ui.words;

import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.WordCard;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

import static com.vocabtrainer.util.Messages.tr;

/**
 * The Word List's status, tag and part-of-speech filters and its Status column, without JavaFX.
 * The rules for weak and mastered words come from {@link WordCard}, so the list agrees with the
 * weak-words review mode and the dashboard. Suspended words are listed under All, Suspended, Leech,
 * Unverified, Unchecked and the tag and part-of-speech filters, never as due, weak or mastered.
 * Unverified lists the words no dictionary had when they were added (tag UNVERIFIED), Unchecked
 * those added while the dictionaries could not be asked (tag UNCHECKED).
 *
 * @param status       one of {@link #STATUSES}, which name the filters, not texts ({@link #statusLabel} names
 *                     them); null means All
 * @param tag          part of a tag, ignoring case; blank matches every word
 * @param partOfSpeech part of the part of speech, ignoring case; blank matches every word
 */
public record WordListFilter(String status, String tag, String partOfSpeech) {
    public static final List<String> STATUSES =
        List.of("All", "Due", "Weak", "Mastered", "Leech", "Suspended", "Unverified", "Unchecked");

    /** What the status filter shows for one of {@link #STATUSES}. */
    public static String statusLabel(String status) {
        return switch (status) {
            case "Due" -> tr("words.status.due");
            case "Weak" -> tr("words.status.weak");
            case "Mastered" -> tr("words.status.mastered");
            case "Leech" -> tr("words.status.leech");
            case "Suspended" -> tr("words.status.suspended");
            case "Unverified" -> tr("words.status.unverified");
            case "Unchecked" -> tr("words.status.unchecked");
            default -> tr("words.status.all");
        };
    }

    /** @param dayEnd the end of the current study day, which decides which words are due today */
    public boolean matches(WordCard word, LocalDateTime now, LocalDateTime dayEnd) {
        if ("Due".equals(status) && !word.isDue(now, dayEnd)) {
            return false;
        }
        if ("Leech".equals(status) && !word.isLeech()) {
            return false;
        }
        if ("Suspended".equals(status) && !word.isSuspended()) {
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
        if ("Unchecked".equals(status) && !word.hasTag("UNCHECKED")) {
            return false;
        }
        if (tag != null && !tag.isBlank() && !containsIgnoreCase(word.getTags(), tag.trim())) {
            return false;
        }
        return partOfSpeech == null || partOfSpeech.isBlank()
            || containsIgnoreCase(word.getPartOfSpeech(), partOfSpeech.trim());
    }

    /**
     * The search box: the words whose English, Chinese or tags contain {@code query}, ignoring case;
     * a blank query matches every word.
     */
    public static Predicate<WordCard> searching(String query) {
        if (query == null || query.isBlank()) {
            return word -> true;
        }
        String needle = query.trim().toLowerCase(Locale.ROOT);
        return word -> containsLowerCase(word.getEnglish(), needle) || containsLowerCase(word.getChinese(), needle)
            || containsLowerCase(word.getTags(), needle);
    }

    /** The Status column: Suspended, Mastered, Due, New or Learning. */
    public static String statusOf(WordCard word, LocalDateTime now, LocalDateTime dayEnd) {
        if (word.isSuspended()) {
            return tr("words.status.suspended");
        }
        if (word.isMastered()) {
            return tr("words.status.mastered");
        }
        if (word.isDue(now, dayEnd)) {
            return tr("words.status.due");
        }
        if (word.getState() == CardState.NEW) {
            return tr("cardState.new");
        }
        return tr("cardState.learning");
    }

    private static boolean containsIgnoreCase(String value, String needle) {
        return needle != null && containsLowerCase(value, needle.toLowerCase(Locale.ROOT));
    }

    private static boolean containsLowerCase(String value, String lowerCaseNeedle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(lowerCaseNeedle);
    }
}
