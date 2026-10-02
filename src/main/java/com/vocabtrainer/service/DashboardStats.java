package com.vocabtrainer.service;

/**
 * @param totalWords        the deck's words, suspended ones included
 * @param dueToday          due reviews and the new words available today: what an All Due session shows
 * @param dueReviews        learning, relearning and review cards that are due
 * @param newAvailableToday new words the deck's new-cards-per-day limit still allows today
 * @param suspendedWords    the deck's suspended words, which are in no other count
 */
public record DashboardStats(
    int totalWords,
    int dueToday,
    int masteredWords,
    int reviewedToday,
    double accuracyToday,
    int dueReviews,
    int newAvailableToday,
    int suspendedWords
) {
}
