package com.vocabtrainer.service;

import java.time.LocalDateTime;

/**
 * A checked answer waiting for its rating.
 *
 * @param responseMillis the time from showing the card to submitting the answer; 0 if unknown
 */
public record ReviewAnswer(
    long wordId,
    String english,
    String userAnswer,
    String correctAnswer,
    double similarity,
    LocalDateTime submittedAt,
    long responseMillis
) {
}
