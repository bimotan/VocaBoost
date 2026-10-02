package com.vocabtrainer.service;

import com.vocabtrainer.domain.ReviewMode;

import java.time.LocalDateTime;

/**
 * A checked answer waiting for its rating.
 *
 * @param responseMillis the time from showing the card to submitting the answer; 0 if unknown
 * @param direction      how the question was asked: {@link ReviewMode#EN_TO_ZH} or {@link ReviewMode#ZH_TO_EN}
 * @param mode           the review mode the answer was given in
 */
public record ReviewAnswer(
    long wordId,
    String english,
    String userAnswer,
    String correctAnswer,
    double similarity,
    LocalDateTime submittedAt,
    long responseMillis,
    ReviewMode direction,
    ReviewMode mode
) {
    /**
     * A recognition question (English to Chinese) of Mixed mode, where the same card is also asked
     * the other way: a success counts at most as Good, see {@link ReviewService}.
     */
    public boolean isRecognitionInMixedMode() {
        return mode == ReviewMode.MIXED && direction == ReviewMode.EN_TO_ZH;
    }
}
