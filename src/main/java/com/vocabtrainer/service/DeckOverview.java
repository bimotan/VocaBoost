package com.vocabtrainer.service;

import com.vocabtrainer.domain.Deck;

import java.time.LocalDateTime;

/**
 * One row of the deck table.
 *
 * @param words          active words in the deck
 * @param due            due reviews and the new words available today, as the dashboard's "Due today"
 * @param latestReviewAt the newest review in the deck, or null if it has none
 */
public record DeckOverview(Deck deck, int words, int due, LocalDateTime latestReviewAt) {
}
