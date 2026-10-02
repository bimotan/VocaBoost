package com.vocabtrainer.service;

import com.vocabtrainer.domain.Deck;

import java.time.LocalDateTime;

/**
 * One row of the deck table.
 *
 * @param words          active words in the deck
 * @param due            active words due now
 * @param latestReviewAt the newest review in the deck, or null if it has none
 */
public record DeckOverview(Deck deck, int words, int due, LocalDateTime latestReviewAt) {
}
