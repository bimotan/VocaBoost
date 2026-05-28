package com.vocabtrainer.service;

import java.time.LocalDateTime;

public record ReviewAnswer(
    long wordId,
    String english,
    String userAnswer,
    String correctAnswer,
    double similarity,
    LocalDateTime submittedAt
) {
}