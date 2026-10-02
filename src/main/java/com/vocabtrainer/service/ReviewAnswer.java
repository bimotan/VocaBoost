package com.vocabtrainer.service;

import com.vocabtrainer.domain.ReviewMode;
import com.vocabtrainer.domain.ReviewRating;

import java.time.LocalDateTime;

/**
 * A checked answer waiting for its rating.
 *
 * @param grade          how the answer was graded: its similarity and the best rating it can count as
 * @param responseMillis the time from showing the card to submitting the answer; 0 if unknown
 * @param direction      how the question was asked: {@link ReviewMode#EN_TO_ZH} or {@link ReviewMode#ZH_TO_EN}
 * @param mode           the review mode the answer was given in
 */
public record ReviewAnswer(
    long wordId,
    String english,
    String userAnswer,
    String correctAnswer,
    AnswerGrade grade,
    LocalDateTime submittedAt,
    long responseMillis,
    ReviewMode direction,
    ReviewMode mode
) {
    /** How similar the answer is to the correct one, from 0 to 1. */
    public double similarity() {
        return grade.similarity();
    }

    /**
     * A recognition question (English to Chinese) of Mixed mode, where the same card is also asked
     * the other way: a success counts at most as Good, see {@link ReviewService}.
     */
    public boolean isRecognitionInMixedMode() {
        return mode == ReviewMode.MIXED && direction == ReviewMode.EN_TO_ZH;
    }

    /**
     * The rating the schedule uses when the user chooses {@code chosen}: at most the grade's
     * {@link AnswerGrade#maxRating() maximum}, unless the user overrode the check
     * ({@code overridden}), and in Mixed mode an Easy recognition counts as Good.
     */
    public ReviewRating countsAs(ReviewRating chosen, boolean overridden) {
        ReviewRating rating = isRecognitionInMixedMode() ? chosen.atMost(ReviewRating.GOOD) : chosen;
        return overridden && canOverride() ? rating : rating.atMost(grade.maxRating());
    }

    /** Whether the check lowers a rating, so the user may override it ("I was right"). */
    public boolean canOverride() {
        return grade.capsRatings();
    }

    /** The rating to suggest: Good for an answer that is right, otherwise the best it can count as. */
    public ReviewRating suggestedRating(boolean overridden) {
        return overridden && canOverride() ? ReviewRating.GOOD : ReviewRating.GOOD.atMost(grade.maxRating());
    }
}
