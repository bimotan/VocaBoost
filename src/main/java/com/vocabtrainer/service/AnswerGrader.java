package com.vocabtrainer.service;

import com.vocabtrainer.domain.ReviewMode;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Grades a typed answer in the direction the question was asked: how similar it is and the best
 * rating it can count as.
 *
 * <p><b>English to Chinese.</b> The best typed meaning against the best meaning of the gloss
 * ({@link SimilarityService#calculate}); the similarity caps the rating
 * ({@link ReviewRating#maxForSimilarity}): a meaning that matches counts as anything, below 55%
 * only as Again.
 *
 * <p><b>Chinese to English.</b> The word, ignoring case, accents, spaces and hyphens, counts as anything.
 * Another real word is wrong (0%), however close its spelling: a well-known confusable such as
 * affect for effect, or another word of the deck, unless that word shares a meaning with the asked
 * gloss: then it is a synonym and counts as right, since the prompt allows it. Otherwise a typo
 * (one letter wrong, missing, extra or swapped with its neighbour) per {@value #LETTERS_PER_TYPO}
 * letters of the word counts at most as Hard, so "lucud" for lucid is Hard; anything further off,
 * and any slip in a shorter word, is Again.
 */
public class AnswerGrader {
    /** A word gets one typo for this many letters: none below 5 letters, one up to 9, two up to 14. */
    static final int LETTERS_PER_TYPO = 5;

    /**
     * Well-known pairs of English words that differ by a letter or two but mean different things;
     * typing one for the other is wrong even when the deck does not have it. Variant spellings of one
     * word (forgo and forego) are not confusables.
     */
    private static final List<List<String>> CONFUSABLES = List.of(
        List.of("affect", "effect"),
        List.of("principal", "principle"),
        List.of("proscribe", "prescribe"),
        List.of("elicit", "illicit"),
        List.of("complement", "compliment"),
        List.of("discreet", "discrete"),
        List.of("eminent", "imminent", "immanent"),
        List.of("allusion", "illusion"),
        List.of("censor", "censure"),
        List.of("flaunt", "flout"),
        List.of("venal", "venial"),
        List.of("ingenious", "ingenuous"),
        List.of("adverse", "averse"),
        List.of("appraise", "apprise"),
        List.of("deprecate", "depreciate"),
        List.of("tortuous", "torturous"),
        List.of("prostate", "prostrate"),
        List.of("canvas", "canvass"),
        List.of("council", "counsel"),
        List.of("stationary", "stationery"),
        List.of("loath", "loathe"),
        List.of("ascent", "assent"),
        List.of("exalt", "exult"),
        List.of("forbear", "forebear"),
        List.of("capital", "capitol"),
        List.of("desert", "dessert"),
        List.of("precede", "proceed"),
        List.of("militate", "mitigate")
    );
    private static final Map<String, Set<String>> CONFUSED_WITH = confusedWith();

    /** Finds an active word of the deck by its English text, ignoring case. */
    @FunctionalInterface
    public interface DeckWords {
        Optional<WordCard> findByEnglish(String english) throws SQLException;
    }

    private final SimilarityService similarity;

    public AnswerGrader(SimilarityService similarity) {
        this.similarity = similarity;
    }

    /**
     * Grades {@code typed} as the answer to {@code word} asked in {@code direction}
     * ({@link ReviewMode#ZH_TO_EN} expects the English word, anything else the Chinese meaning).
     *
     * @param deckWords the words of the deck, to tell another word or a synonym from a typo
     */
    public AnswerGrade grade(WordCard word, ReviewMode direction, String typed, DeckWords deckWords)
        throws SQLException {
        return direction == ReviewMode.ZH_TO_EN
            ? gradeEnglish(word, typed, deckWords)
            : gradeMeaning(typed, word.getChinese());
    }

    /** Grades a typed Chinese meaning against {@code gloss}; see the class comment. */
    public AnswerGrade gradeMeaning(String typed, String gloss) {
        return AnswerGrade.bySimilarity(similarity.calculate(typed, gloss));
    }

    /** Grades a typed English word for {@code word}; see the class comment. */
    public AnswerGrade gradeEnglish(WordCard word, String typed, DeckWords deckWords) throws SQLException {
        String expected = word.getEnglish();
        String answer = typed == null ? "" : typed.trim();
        if (similarity.letters(answer).isEmpty()) {
            return new AnswerGrade(0.0, ReviewRating.AGAIN, AnswerGrade.Verdict.WRONG, null, null);
        }
        int distance = similarity.spellingDistance(answer, expected);
        if (distance == 0 || similarity.sameEnglish(answer, expected)) {
            return new AnswerGrade(1.0, ReviewRating.EASY, AnswerGrade.Verdict.MATCH, null, null);
        }
        if (isConfusable(answer, expected)) {
            return new AnswerGrade(0.0, ReviewRating.AGAIN, AnswerGrade.Verdict.CONFUSABLE, answer, null);
        }
        Optional<WordCard> other = deckWords == null
            ? Optional.empty()
            : deckWords.findByEnglish(answer).filter(found -> found.getId() != word.getId());
        if (other.isPresent()) {
            WordCard otherWord = other.get();
            return sharesMeaning(otherWord.getChinese(), word.getChinese())
                ? new AnswerGrade(1.0, ReviewRating.EASY, AnswerGrade.Verdict.SYNONYM, otherWord.getEnglish(),
                    otherWord.getChinese())
                : new AnswerGrade(0.0, ReviewRating.AGAIN, AnswerGrade.Verdict.CONFUSABLE, otherWord.getEnglish(),
                    otherWord.getChinese());
        }
        double score = similarity.englishSimilarity(answer, expected);
        String letters = similarity.letters(expected);
        int typosAllowed = letters.codePointCount(0, letters.length()) / LETTERS_PER_TYPO;
        return distance <= typosAllowed
            ? new AnswerGrade(score, ReviewRating.HARD, AnswerGrade.Verdict.MISSPELLED, null, null)
            : new AnswerGrade(score, ReviewRating.AGAIN, AnswerGrade.Verdict.WRONG, null, null);
    }

    /** Whether the two glosses have a meaning in common (see {@link SimilarityService#meaningKeys}). */
    public boolean sharesMeaning(String gloss, String otherGloss) {
        Set<String> shared = new HashSet<>(similarity.meaningKeys(gloss));
        shared.retainAll(similarity.meaningKeys(otherGloss));
        return !shared.isEmpty();
    }

    /** Whether {@code typed} is a well-known confusable of {@code expected}, such as affect for effect. */
    static boolean isConfusable(String typed, String expected) {
        Set<String> confused = CONFUSED_WITH.get(key(expected));
        return confused != null && confused.contains(key(typed));
    }

    private static String key(String english) {
        return english == null ? "" : english.trim().toLowerCase(Locale.ROOT);
    }

    private static Map<String, Set<String>> confusedWith() {
        Map<String, Set<String>> confusedWith = new HashMap<>();
        for (List<String> group : CONFUSABLES) {
            for (String word : group) {
                Set<String> others = new HashSet<>(group);
                others.remove(word);
                confusedWith.computeIfAbsent(word, ignored -> new HashSet<>()).addAll(others);
            }
        }
        return Map.copyOf(confusedWith);
    }
}
