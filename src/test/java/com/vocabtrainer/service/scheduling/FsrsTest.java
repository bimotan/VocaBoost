package com.vocabtrainer.service.scheduling;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FSRS-5 arithmetic against values worked out by hand from the published formulas and default
 * parameters (and matching the reference implementation, py-fsrs 5.1.3).
 */
class FsrsTest {
    private static final double EPSILON = 1e-6;
    private final Fsrs fsrs = new Fsrs();

    @Test
    void theDefaultParametersAreThePublishedOnes() {
        assertArrayEquals(new double[] {0.40255, 1.18385, 3.173, 15.69105, 7.1949, 0.5345, 1.4604, 0.0046, 1.54575,
            0.1192, 1.01925, 1.9395, 0.11, 0.29605, 2.2698, 0.2315, 2.9898, 0.51655, 0.6621}, Fsrs.defaultParameters());
        assertEquals(19.0 / 81.0, Math.pow(0.9, 1 / Fsrs.DECAY) - 1, 1e-12);
    }

    @Test
    void aFirstReviewSetsStabilityAndDifficultyFromTheGrade() {
        // S0(G) = w[G-1]
        assertEquals(0.40255, fsrs.initialStability(1), EPSILON);
        assertEquals(1.18385, fsrs.initialStability(2), EPSILON);
        assertEquals(3.173, fsrs.initialStability(3), EPSILON);
        assertEquals(15.69105, fsrs.initialStability(4), EPSILON);
        // D0(G) = w4 - e^(w5 (G - 1)) + 1: 7.1949 - 1 + 1, 8.1949 - e^0.5345, 8.1949 - e^1.069, 8.1949 - e^1.6035
        assertEquals(7.1949, fsrs.initialDifficulty(1), EPSILON);
        assertEquals(6.488305, fsrs.initialDifficulty(2), EPSILON);
        assertEquals(5.282434, fsrs.initialDifficulty(3), EPSILON);
        assertEquals(3.224502, fsrs.initialDifficulty(4), EPSILON);

        Fsrs.Memory good = fsrs.next(null, 0, 3);
        assertEquals(3.173, good.stability(), EPSILON);
        assertEquals(5.282434, good.difficulty(), EPSILON);
    }

    @Test
    void retrievabilityIsNinetyPercentAfterStabilityDaysAndTheIntervalEqualsStability() {
        assertEquals(1.0, Fsrs.retrievability(0, 10), EPSILON);
        assertEquals(0.9, Fsrs.retrievability(10, 10), EPSILON);
        // (1 + 19/81 * 30/10)^-0.5 = (1.703704)^-0.5
        assertEquals(0.766131, Fsrs.retrievability(30, 10), EPSILON);
        assertEquals(0.0, Fsrs.retrievability(5, 0), EPSILON);

        assertEquals(10.0, fsrs.rawInterval(10), EPSILON);
        assertEquals(16, fsrs.nextInterval(15.69105));
        assertEquals(1, fsrs.nextInterval(0.40255), "at least a day");
        // r = 0.8: S / FACTOR * (0.8^-2 - 1) = 10 * 81/19 * 0.5625
        assertEquals(23.980263, new Fsrs(0.8, 36500).rawInterval(10), EPSILON);
    }

    @Test
    void theMaximumIntervalCapsLongIntervals() {
        assertEquals(36500, fsrs.nextInterval(1_000_000));
        assertEquals(100, new Fsrs(0.9, 100).nextInterval(400));
    }

    @Test
    void difficultyRisesWithAgainAndHardAndFallsWithEasy() {
        // dD = -w6 (G - 3); D' = D + dD (10 - D) / 9; D'' = w7 D0(4) + (1 - w7) D'
        assertEquals(6.607035, fsrs.nextDifficulty(5, 1), EPSILON);
        assertEquals(5.799434, fsrs.nextDifficulty(5, 2), EPSILON);
        assertEquals(4.991833, fsrs.nextDifficulty(5, 3), EPSILON);
        assertEquals(4.184232, fsrs.nextDifficulty(5, 4), EPSILON);
        // No room left to rise; mean reversion still pulls it a little towards D0(4).
        assertEquals(9.968833, fsrs.nextDifficulty(10, 1), EPSILON);
        assertEquals(1.0, fsrs.nextDifficulty(1, 4), 0.02);
    }

    @Test
    void aSuccessfulReviewOnTimeMultipliesStabilityByGrade() {
        // S = 10, D = 5, t = 10 days (R = 0.9):
        // S' = 10 (e^1.54575 * 6 * 10^-0.1192 * (e^(1.01925 * 0.1) - 1) * penalty/bonus + 1)
        assertEquals(15.313912, fsrs.recallStability(5, 10, 0.9, 2), EPSILON);
        assertEquals(32.954264, fsrs.recallStability(5, 10, 0.9, 3), EPSILON);
        assertEquals(78.628658, fsrs.recallStability(5, 10, 0.9, 4), EPSILON);

        Fsrs.Memory after = fsrs.next(new Fsrs.Memory(10, 5), 10, 3);
        assertEquals(32.954264, after.stability(), EPSILON);
        assertEquals(4.991833, after.difficulty(), EPSILON);
    }

    @Test
    void forgettingLowersStabilityBelowWhatItWas() {
        // min(1.9395 * 5^-0.11 * (11^0.29605 - 1) * e^(2.2698 * 0.1), 10 / e^(0.51655 * 0.6621))
        assertEquals(2.107696, fsrs.forgetStability(5, 10, 0.9), EPSILON);
        // A young card: the long-term formula would exceed S, the cap S / e^(w17 w18) applies.
        assertEquals(0.5 / Math.exp(0.51655 * 0.6621), fsrs.forgetStability(1, 0.5, 0.2), EPSILON);
    }

    @Test
    void anOverdueSuccessGrowsStabilityMoreThanAnEarlyOne() {
        Fsrs.Memory before = new Fsrs.Memory(10, 5);

        double early = fsrs.next(before, 1, 3).stability();
        double onTime = fsrs.next(before, 10, 3).stability();
        double overdue = fsrs.next(before, 30, 3).stability();

        assertEquals(12.527990, early, EPSILON);
        assertEquals(67.584407, overdue, EPSILON);
        assertTrue(early < onTime && onTime < overdue);
        // A review the day after barely changes a 10-day memory.
        assertTrue(early / before.stability() < 1.3, "early review gives " + early);
    }

    @Test
    void aReviewOnTheSameDayUsesShortTermStability() {
        // S' = S e^(w17 (G - 3 + w18))
        assertEquals(1.589764, fsrs.shortTermStability(3.173, 1), EPSILON);
        assertEquals(2.664817, fsrs.shortTermStability(3.173, 2), EPSILON);
        assertEquals(4.466858, fsrs.shortTermStability(3.173, 3), EPSILON);
        assertEquals(7.487502, fsrs.shortTermStability(3.173, 4), EPSILON);
        assertEquals(4.466858, fsrs.next(new Fsrs.Memory(3.173, 5.282434), 0, 3).stability(), EPSILON);
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> fsrs.initialStability(0));
        assertThrows(IllegalArgumentException.class, () -> fsrs.next(null, 0, 5));
        assertThrows(IllegalArgumentException.class, () -> new Fsrs(1.0, 100));
        assertThrows(IllegalArgumentException.class, () -> new Fsrs(0.9, 0));
        assertThrows(IllegalArgumentException.class, () -> new Fsrs(new double[] {1, 2}, 0.9, 100));
    }
}
