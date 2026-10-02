package com.vocabtrainer.service;

import com.vocabtrainer.domain.ReviewRating;

/**
 * How a typed answer was graded (see {@link AnswerGrader}).
 *
 * @param similarity how close the answer is to the correct one, from 0 to 1; shown and logged
 * @param maxRating  the best rating the answer can count as unless the user overrides the check
 * @param verdict    why
 * @param otherWord  for {@link Verdict#SYNONYM} and {@link Verdict#CONFUSABLE}, the word that was typed
 *                   instead; otherwise null
 * @param otherGloss the Chinese gloss of {@code otherWord} when it is a word of the deck; otherwise null
 */
public record AnswerGrade(double similarity, ReviewRating maxRating, Verdict verdict, String otherWord,
                          String otherGloss) {
    public enum Verdict {
        /** The answer is right: one of the meanings, or the English word. */
        MATCH,
        /** Another word of the deck that shares a meaning with the asked gloss: counts as right. */
        SYNONYM,
        /** The English word with a typo: counts at most as Hard. */
        MISSPELLED,
        /** Another English word (of the deck, or a well-known confusable pair) that does not mean this: wrong. */
        CONFUSABLE,
        /** A meaning that is close but not equal: capped by its similarity. */
        PARTIAL,
        /** Not close enough to count as anything but Again. */
        WRONG
    }

    public AnswerGrade {
        if (maxRating == null || verdict == null) {
            throw new IllegalArgumentException("A grade needs a maximum rating and a verdict");
        }
    }

    /** A grade capped only by the similarity, as answers were graded before verdicts existed. */
    public static AnswerGrade bySimilarity(double similarity) {
        ReviewRating cap = ReviewRating.maxForSimilarity(similarity);
        Verdict verdict = similarity >= 1.0 ? Verdict.MATCH : cap == ReviewRating.AGAIN ? Verdict.WRONG : Verdict.PARTIAL;
        return new AnswerGrade(similarity, cap, verdict, null, null);
    }

    /** Whether the check lowers at least one rating, so the user may override it. */
    public boolean capsRatings() {
        return maxRating != ReviewRating.EASY;
    }
}
