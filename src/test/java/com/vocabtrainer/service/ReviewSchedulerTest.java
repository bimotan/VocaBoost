package com.vocabtrainer.service;

import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewSchedulerTest {
    private final ReviewScheduler scheduler = new ReviewScheduler(new Random(1));

    @Test
    void againResetsStreakAndSchedulesTomorrow() {
        WordCard word = WordCard.createNew(1, "aver", "断言");
        word.setConsecutiveCorrect(2);
        word.setIntervalDays(7);
        LocalDateTime now = LocalDateTime.of(2026, 5, 28, 9, 0);

        scheduler.applyRating(word, ReviewRating.AGAIN, now);

        assertEquals(0, word.getConsecutiveCorrect());
        assertEquals(1, word.getIntervalDays());
        assertEquals(1, word.getLapses());
        assertEquals(now.plusDays(1), word.getNextReviewAt());
    }

    @Test
    void goodUsesInitialIntervalsForFirstThreeCorrectReviews() {
        WordCard word = WordCard.createNew(1, "rote", "死记硬背");
        LocalDateTime now = LocalDateTime.of(2026, 5, 28, 9, 0);

        scheduler.applyRating(word, ReviewRating.GOOD, now);
        assertEquals(1, word.getIntervalDays());
        scheduler.applyRating(word, ReviewRating.GOOD, now.plusDays(1));
        assertEquals(3, word.getIntervalDays());
        scheduler.applyRating(word, ReviewRating.GOOD, now.plusDays(4));
        assertEquals(7, word.getIntervalDays());
    }

    @Test
    void easyIncreasesEasinessAndLaterInterval() {
        WordCard word = WordCard.createNew(1, "forthright", "直率的");
        word.setConsecutiveCorrect(3);
        word.setIntervalDays(7);
        LocalDateTime now = LocalDateTime.of(2026, 5, 28, 9, 0);

        scheduler.applyRating(word, ReviewRating.EASY, now);

        assertEquals(4, word.getConsecutiveCorrect());
        assertTrue(word.getEasinessFactor() > WordCard.DEFAULT_EASINESS);
        assertTrue(word.getIntervalDays() > 7);
    }
}