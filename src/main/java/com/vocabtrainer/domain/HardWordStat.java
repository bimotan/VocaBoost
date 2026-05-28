package com.vocabtrainer.domain;

public record HardWordStat(
    String english,
    String chinese,
    int reviewCount,
    double averageSimilarity,
    int againCount
) {
}
