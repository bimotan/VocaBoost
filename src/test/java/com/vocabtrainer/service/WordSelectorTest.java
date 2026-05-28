package com.vocabtrainer.service;

import com.vocabtrainer.domain.WordCard;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WordSelectorTest {
    @Test
    void lapsedWordsReceiveHigherSelectionWeight() {
        WordSelector selector = new WordSelector(new Random(1));
        LocalDateTime now = LocalDateTime.of(2026, 5, 28, 9, 0);
        WordCard stable = WordCard.createNew(1, "lucid", "清晰的");
        stable.setAddedAt(now.minusDays(10));
        stable.setLastReviewedAt(now.minusDays(2));
        stable.setNextReviewAt(now.minusDays(1));
        stable.setConsecutiveCorrect(2);

        WordCard fuzzy = WordCard.createNew(1, "abate", "减弱");
        fuzzy.setAddedAt(stable.getAddedAt());
        fuzzy.setLastReviewedAt(stable.getLastReviewedAt());
        fuzzy.setNextReviewAt(stable.getNextReviewAt());
        fuzzy.setConsecutiveCorrect(2);
        fuzzy.setLapses(3);

        assertTrue(selector.calculateWeight(fuzzy, now) > selector.calculateWeight(stable, now));
    }
}
