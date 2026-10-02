package com.vocabtrainer.service;

/**
 * @param dueToday          due reviews and the new words available today: what an All Due session shows
 * @param dueReviews        learning, relearning and review cards that are due
 * @param newAvailableToday new words the deck's new-cards-per-day limit still allows today
 */
public record DashboardStats(
    int totalWords,
    int dueToday,
    int masteredWords,
    int reviewedToday,
    double accuracyToday,
    int dueReviews,
    int newAvailableToday
) {
}
