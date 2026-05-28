package com.vocabtrainer.domain;

import java.time.LocalDate;

public record DailyReviewStat(
    LocalDate date,
    int reviewCount,
    double accuracy
) {
}
