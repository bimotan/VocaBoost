package com.vocabtrainer.service;

import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.WordCard;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * Picks the next card among due ones at random, weighted by how likely the card is forgotten by
 * now: a card's weight is 1 minus its FSRS retrievability, so an overdue card or one with a weak
 * memory comes sooner. A new card weighs as much as a review card at its due date (90% recall).
 */
public class WordSelector {
    /** The weight of a card never reviewed: that of a card due today at the default 90% retention. */
    static final double NEW_CARD_WEIGHT = 0.1;
    private static final double MIN_WEIGHT = 0.01;

    private final Random random;

    public WordSelector() {
        this(new Random());
    }

    public WordSelector(Random random) {
        this.random = random;
    }

    public Optional<WordCard> selectNext(List<WordCard> dueWords, LocalDateTime now) {
        if (dueWords == null || dueWords.isEmpty()) {
            return Optional.empty();
        }

        double totalWeight = 0.0;
        double[] weights = new double[dueWords.size()];
        for (int i = 0; i < dueWords.size(); i++) {
            weights[i] = calculateWeight(dueWords.get(i), now);
            totalWeight += weights[i];
        }

        double point = random.nextDouble() * totalWeight;
        double cumulative = 0.0;
        for (int i = 0; i < dueWords.size(); i++) {
            cumulative += weights[i];
            if (cumulative >= point) {
                return Optional.of(dueWords.get(i));
            }
        }
        return Optional.of(dueWords.get(dueWords.size() - 1));
    }

    /** The chance the card is forgotten by {@code now}, at least 0.01; lapses long ago do not count. */
    public double calculateWeight(WordCard word, LocalDateTime now) {
        if (word.getState() == CardState.NEW) {
            return NEW_CARD_WEIGHT;
        }
        double recall = ReviewScheduler.retrievability(word, now).orElse(1.0 - NEW_CARD_WEIGHT);
        return Math.max(MIN_WEIGHT, 1.0 - recall);
    }
}
