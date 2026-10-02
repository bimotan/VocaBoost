package com.vocabtrainer.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "To finish N new words before the exam you need ~X new words/day" (review finding G5). */
class NewCardPlanTest {
    @Test
    void theWordsAreSpreadOverTheDaysLeftTodayIncluded() {
        assertEquals(5, NewCardPlan.wordsPerDay(215, 0, 45), "215 / 45 = 4.8");
        assertEquals(50, NewCardPlan.wordsPerDay(2000, 0, 40));
        assertEquals(51, NewCardPlan.wordsPerDay(2001, 0, 40));
        assertEquals(1, NewCardPlan.wordsPerDay(1, 0, 30));
        assertEquals(0, NewCardPlan.wordsPerDay(0, 12, 30), "nothing left to introduce");
    }

    @Test
    void wordsAlreadyIntroducedTodayCountAgainstTodaysLimit() {
        // A limit of 12 lets in 2 more today and 12 on each of the 9 later days: 110.
        assertEquals(12, NewCardPlan.wordsPerDay(110, 10, 10));
        // Today already went past what the later days need: 100 words over 10 later days.
        assertEquals(10, NewCardPlan.wordsPerDay(100, 30, 11));
        // The exam is tomorrow: everything has to come today, on top of today's 4.
        assertEquals(24, NewCardPlan.wordsPerDay(20, 4, 1));
    }

    /** The formula against the definition: the smallest limit whose days add up to every new word. */
    @Test
    void theLimitIsTheSmallestThatIntroducesEveryWordInTime() {
        for (int newWords = 0; newWords <= 60; newWords++) {
            for (int introduced = 0; introduced <= 15; introduced++) {
                for (int days = 1; days <= 20; days++) {
                    int limit = 0;
                    while (capacity(limit, introduced, days) < newWords) {
                        limit++;
                    }
                    assertEquals(limit, NewCardPlan.wordsPerDay(newWords, introduced, days),
                        newWords + " words, " + introduced + " introduced today, " + days + " days");
                }
            }
        }
    }

    @Test
    void thePlanSaysWhetherTheLimitIsEnough() {
        NewCardPlan behind = NewCardPlan.of(1240, 0, 45, 20);
        assertEquals(28, behind.wordsPerDay());
        assertFalse(behind.isOnTrack());
        assertEquals(28, behind.limitToApply());
        assertEquals("To finish 1,240 new words before the exam you need ~28 new words/day (now 20).",
            behind.toDisplayText());

        NewCardPlan onTrack = NewCardPlan.of(215, 0, 45, 20);
        assertTrue(onTrack.isOnTrack());
        assertEquals("To finish 215 new words before the exam you need ~5 new words/day; the limit of 20/day is enough.",
            onTrack.toDisplayText());

        NewCardPlan done = NewCardPlan.of(0, 3, 10, 20);
        assertTrue(done.isDone());
        assertEquals("Every new word of this deck has been started.", done.toDisplayText());

        NewCardPlan huge = NewCardPlan.of(30_000, 0, 1, 20);
        assertEquals(30_000, huge.wordsPerDay());
        assertEquals(ReviewSettings.MAX_NEW_CARDS_PER_DAY, huge.limitToApply(), "the most the limit can be");

        assertThrows(IllegalArgumentException.class, () -> NewCardPlan.of(10, 0, 0, 20), "no day left");
    }

    /** How many new words a limit lets in: what is left of it today, then the whole limit on every later day. */
    private static long capacity(int limit, int introducedToday, int days) {
        return Math.max(0, limit - introducedToday) + (long) limit * (days - 1);
    }
}
