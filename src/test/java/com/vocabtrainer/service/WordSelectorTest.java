package com.vocabtrainer.service;

import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.WordCard;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WordSelectorTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 5, 28, 9, 0);

    private final WordSelector selector = new WordSelector(new Random(1));

    @Test
    void aWordMoreLikelyForgottenWeighsMore() {
        WordCard onTime = reviewCard("lucid", 10, NOW.minusDays(10));
        WordCard overdue = reviewCard("abate", 10, NOW.minusDays(40));
        WordCard fresh = reviewCard("laud", 10, NOW.minusHours(1));

        double onTimeWeight = selector.calculateWeight(onTime, NOW);

        assertEquals(0.1, onTimeWeight, 1e-9, "1 - R at the due date");
        assertTrue(selector.calculateWeight(overdue, NOW) > onTimeWeight);
        assertEquals(0.01, selector.calculateWeight(fresh, NOW), 1e-9, "never below 1%");
    }

    @Test
    void lapsesLongAgoDoNotRaiseTheWeight() {
        WordCard steady = reviewCard("lucid", 10, NOW.minusDays(10));
        WordCard lapsedOnce = reviewCard("abate", 10, NOW.minusDays(10));
        lapsedOnce.setLapses(3);

        assertEquals(selector.calculateWeight(steady, NOW), selector.calculateWeight(lapsedOnce, NOW), 1e-12);
    }

    @Test
    void aNewWordWeighsAsMuchAsAReviewAtItsDueDate() {
        assertEquals(WordSelector.NEW_CARD_WEIGHT, selector.calculateWeight(WordCard.createNew(1, "new", "新", NOW), NOW));
    }

    @Test
    void theOverdueWordIsPickedMostOften() {
        WordCard overdue = reviewCard("abate", 2, NOW.minusDays(60));
        WordCard fresh = reviewCard("lucid", 10, NOW.minusDays(1));
        int overduePicks = 0;
        for (int i = 0; i < 200; i++) {
            if (selector.selectNext(List.of(fresh, overdue), NOW).orElseThrow() == overdue) {
                overduePicks++;
            }
        }
        assertTrue(overduePicks > 150, "picked " + overduePicks + " of 200");
    }

    private static WordCard reviewCard(String english, double stability, LocalDateTime lastReview) {
        WordCard card = WordCard.createNew(1, english, "词", NOW);
        card.setState(CardState.REVIEW);
        card.setStability(stability);
        card.setDifficulty(5);
        card.setRepetitions(3);
        card.setConsecutiveCorrect(3);
        card.setLastReviewedAt(lastReview);
        card.setNextReviewAt(lastReview.plusDays(Math.round(stability)));
        return card;
    }
}
