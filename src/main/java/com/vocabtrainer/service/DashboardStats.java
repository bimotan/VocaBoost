package com.vocabtrainer.service;

public record DashboardStats(
    int totalWords,
    int dueToday,
    int masteredWords,
    int reviewedToday,
    double accuracyToday
) {
}