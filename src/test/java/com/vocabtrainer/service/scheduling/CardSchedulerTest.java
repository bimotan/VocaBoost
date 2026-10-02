package com.vocabtrainer.service.scheduling;

import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CardSchedulerTest {
    private static final double EPSILON = 1e-5;
    private static final LocalDateTime MONDAY = LocalDateTime.of(2026, 3, 2, 9, 0);

    private final CardScheduler scheduler = new CardScheduler(SchedulingOptions.defaults());

    @Test
    void aNewCardGoesThroughTheLearningSteps() {
        Map<ReviewRating, CardScheduler.Outcome> first = scheduler.outcomes(newCard(1), MONDAY);

        assertStep(first.get(ReviewRating.AGAIN), CardState.LEARNING, 0, MONDAY.plusMinutes(1));
        assertStep(first.get(ReviewRating.HARD), CardState.LEARNING, 0, MONDAY.plusSeconds(330));
        assertStep(first.get(ReviewRating.GOOD), CardState.LEARNING, 1, MONDAY.plusMinutes(10));
        CardScheduler.Outcome easy = first.get(ReviewRating.EASY);
        assertEquals(CardState.REVIEW, easy.state());
        assertInFuzzRange(16, easy.intervalDays());
        assertEquals(MONDAY.toLocalDate().plusDays(easy.intervalDays()).atTime(4, 0), easy.due());
        assertFalse(first.values().stream().anyMatch(CardScheduler.Outcome::lapse));

        WordCard card = newCard(1);
        scheduler.apply(card, ReviewRating.GOOD, MONDAY);
        assertEquals(CardState.LEARNING, card.getState());
        assertEquals(1, card.getLearningStep());
        assertEquals(0, card.getIntervalDays());
        assertEquals(MONDAY.plusMinutes(10), card.getNextReviewAt());
        assertEquals(1, card.getRepetitions());
        assertEquals(1, card.getConsecutiveCorrect());

        CardScheduler.Outcome again = scheduler.outcome(card, ReviewRating.AGAIN, MONDAY.plusMinutes(10));
        assertStep(again, CardState.LEARNING, 0, MONDAY.plusMinutes(11));
        assertFalse(again.lapse(), "failing a learning step is not a lapse");
    }

    /** The same reviews as the FSRS-5 reference scheduler (py-fsrs 5.1.3) gives these states and memories. */
    @Test
    void aReviewHistoryMatchesTheReferenceScheduler() {
        WordCard card = newCard(7);

        apply(card, ReviewRating.GOOD, MONDAY, CardState.LEARNING, 3.173, 5.282434);
        apply(card, ReviewRating.GOOD, MONDAY.plusMinutes(10), CardState.REVIEW, 4.466858, 5.272968);
        assertInFuzzRange(4, card.getIntervalDays());
        LocalDateTime friday = LocalDateTime.of(2026, 3, 6, 9, 10);
        apply(card, ReviewRating.GOOD, friday, CardState.REVIEW, 14.217284, 5.263545);
        assertInFuzzRange(14, card.getIntervalDays());
        LocalDateTime lapse = LocalDateTime.of(2026, 3, 21, 9, 10);
        apply(card, ReviewRating.AGAIN, lapse, CardState.RELEARNING, 2.538392, 6.784232);
        assertEquals(lapse.plusMinutes(10), card.getNextReviewAt());
        assertEquals(1, card.getLapses());
        assertEquals(0, card.getConsecutiveCorrect());
        apply(card, ReviewRating.GOOD, lapse.plusMinutes(10), CardState.REVIEW, 3.573476, 6.767857);
        apply(card, ReviewRating.GOOD, LocalDateTime.of(2026, 3, 24, 9, 20), CardState.REVIEW, 9.15507, 6.751558);

        assertEquals(6, card.getRepetitions());
        assertEquals(2, card.getConsecutiveCorrect());
        assertEquals(WordCard.easinessFromDifficulty(card.getDifficulty()), card.getEasinessFactor(), EPSILON);
    }

    @Test
    void anOverdueSuccessGrowsTheIntervalMoreThanAnEarlyOne() {
        LocalDateTime lastReview = MONDAY;
        WordCard early = reviewCard(3, 10, 5, lastReview);
        WordCard overdue = reviewCard(3, 10, 5, lastReview);

        CardScheduler.Outcome earlyGood = scheduler.outcome(early, ReviewRating.GOOD, lastReview.plusDays(1));
        CardScheduler.Outcome onTimeGood = scheduler.outcome(early, ReviewRating.GOOD, lastReview.plusDays(10));
        CardScheduler.Outcome overdueGood = scheduler.outcome(overdue, ReviewRating.GOOD, lastReview.plusDays(30));

        assertEquals(12.527990, earlyGood.stability(), EPSILON);
        assertEquals(32.954264, onTimeGood.stability(), EPSILON);
        assertEquals(67.584407, overdueGood.stability(), EPSILON);
        assertTrue(earlyGood.intervalDays() < onTimeGood.intervalDays());
        assertTrue(onTimeGood.intervalDays() < overdueGood.intervalDays());
    }

    @Test
    void reviewingEarlyBarelyRaisesStability() {
        WordCard card = reviewCard(4, 30, 5, MONDAY);

        // Weak-words mode may show a card the same day or the day after it was reviewed.
        double sameDay = scheduler.outcome(card, ReviewRating.GOOD, MONDAY.plusHours(2)).stability();
        double nextDay = scheduler.outcome(card, ReviewRating.GOOD, MONDAY.plusDays(1)).stability();

        assertTrue(sameDay / 30 < 1.5, "same day: " + sameDay);
        assertTrue(nextDay / 30 < 1.1, "next day: " + nextDay);
        // The old SM-2 variant multiplied the interval by the easiness factor (2.5) whenever it was shown.
    }

    @Test
    void hardGoodAndEasyGiveIncreasingIntervals() {
        for (long id = 1; id <= 200; id++) {
            for (double stability : new double[] {0.5, 2, 3.5, 10, 45, 400}) {
                WordCard card = reviewCard(id, stability, 1 + id % 10, MONDAY);
                LocalDateTime now = MONDAY.plusDays(Math.max(1, Math.round(stability)));
                Map<ReviewRating, CardScheduler.Outcome> outcomes = scheduler.outcomes(card, now);
                int hard = outcomes.get(ReviewRating.HARD).intervalDays();
                int good = outcomes.get(ReviewRating.GOOD).intervalDays();
                int easy = outcomes.get(ReviewRating.EASY).intervalDays();
                assertTrue(hard >= 1 && hard < good && good < easy,
                    "card " + id + ", S " + stability + ": " + hard + " / " + good + " / " + easy);
                assertEquals(CardState.RELEARNING, outcomes.get(ReviewRating.AGAIN).state());
                assertTrue(outcomes.get(ReviewRating.AGAIN).lapse());
            }
        }
    }

    @Test
    void intervalsNeverExceedTheMaximum() {
        CardScheduler capped = new CardScheduler(SchedulingOptions.defaults().withMaximumInterval(30));
        WordCard card = reviewCard(5, 200, 3, MONDAY);

        Map<ReviewRating, CardScheduler.Outcome> outcomes = capped.outcomes(card, MONDAY.plusDays(200));

        assertEquals(30, outcomes.get(ReviewRating.EASY).intervalDays());
        assertEquals(30, outcomes.get(ReviewRating.GOOD).intervalDays());
        assertTrue(outcomes.get(ReviewRating.HARD).intervalDays() <= 30);
        WordCard old = reviewCard(5, 30_000, 1, MONDAY);
        int easy = scheduler.outcome(old, ReviewRating.EASY, MONDAY.plusDays(30_000)).intervalDays();
        assertTrue(easy > 30_000 && easy <= 36500, "easy: " + easy);
    }

    @Test
    void fuzzIsTheSameForTheSameCardAndReviewAndSpreadsDifferentCards() {
        LocalDateTime now = MONDAY.plusDays(10);
        int first = scheduler.outcome(reviewCard(42, 10, 5, MONDAY), ReviewRating.GOOD, now).intervalDays();
        for (int i = 0; i < 5; i++) {
            assertEquals(first, scheduler.outcome(reviewCard(42, 10, 5, MONDAY), ReviewRating.GOOD, now).intervalDays());
        }

        Set<Integer> intervals = new HashSet<>();
        for (long id = 1; id <= 100; id++) {
            int days = scheduler.outcome(reviewCard(id, 10, 5, MONDAY), ReviewRating.GOOD, now).intervalDays();
            assertInFuzzRange(33, days);
            intervals.add(days);
        }
        assertTrue(intervals.size() >= 4, "fuzz spreads cards learned together: " + intervals);

        // Under three days there is no fuzz.
        for (long id = 1; id <= 50; id++) {
            assertEquals(2, scheduler.outcome(reviewCard(id, 1, 10, MONDAY), ReviewRating.GOOD, MONDAY.plusDays(1))
                .intervalDays());
        }
    }

    @Test
    void fuzzRangesFollowTheReferenceScheduler() {
        assertEquals(2, CardScheduler.fuzzedInterval(2, 0.99, 1, 36500));
        assertEquals(3, CardScheduler.fuzzedInterval(4, 0.0, 1, 36500));
        assertEquals(5, CardScheduler.fuzzedInterval(4, 0.99, 1, 36500));
        assertEquals(13, CardScheduler.fuzzedInterval(16, 0.0, 1, 36500));
        assertEquals(19, CardScheduler.fuzzedInterval(16, 0.999, 1, 36500));
        assertEquals(17, CardScheduler.fuzzedInterval(16, 0.0, 17, 36500), "the minimum wins");
        assertEquals(100, CardScheduler.fuzzedInterval(400, 0.999, 1, 100), "the maximum wins");
        assertEquals(93, CardScheduler.fuzzedInterval(400, 0.0, 1, 100));
    }

    @Test
    void aReviewIsDueWhenItsStudyDayStarts() {
        WordCard card = reviewCard(9, 1, 10, LocalDateTime.of(2026, 3, 9, 21, 30));
        LocalDateTime evening = LocalDateTime.of(2026, 3, 10, 21, 30);

        CardScheduler.Outcome hard = scheduler.outcome(card, ReviewRating.HARD, evening);

        assertEquals(1, hard.intervalDays());
        assertEquals(LocalDateTime.of(2026, 3, 11, 4, 0), hard.due(), "the next morning, not 24 hours later");
        assertEquals(IntervalPreview.days(1), hard.preview(evening));
        // After midnight but before 4 am it is still the 10th: one day since the review on the 9th.
        CardScheduler.Outcome lateNight = scheduler.outcome(card, ReviewRating.GOOD, LocalDateTime.of(2026, 3, 11, 1, 0));
        CardScheduler.Outcome morning = scheduler.outcome(card, ReviewRating.GOOD, LocalDateTime.of(2026, 3, 11, 5, 0));
        assertTrue(lateNight.stability() < morning.stability());
        assertEquals(LocalDate.of(2026, 3, 10).plusDays(lateNight.intervalDays()).atTime(4, 0), lateNight.due());
    }

    @Test
    void aLapseGoesThroughTheRelearningStep() {
        WordCard card = reviewCard(11, 20, 5, MONDAY);
        LocalDateTime now = MONDAY.plusDays(20);

        scheduler.apply(card, ReviewRating.AGAIN, now);

        assertEquals(CardState.RELEARNING, card.getState());
        assertEquals(now.plusMinutes(10), card.getNextReviewAt());
        assertEquals(1, card.getLapses());
        assertEquals(0, card.getIntervalDays());
        assertEquals(Duration.ofMinutes(15),
            scheduler.outcome(card, ReviewRating.HARD, now).preview(now).learningDelay(), "Hard: 1.5 times the single step");

        scheduler.apply(card, ReviewRating.GOOD, now.plusMinutes(10));

        assertEquals(CardState.REVIEW, card.getState());
        assertEquals(1, card.getLapses());
        assertTrue(card.getStability() < 20);
    }

    private void apply(WordCard card, ReviewRating rating, LocalDateTime at, CardState state, double stability,
                       double difficulty) {
        scheduler.apply(card, rating, at);
        assertEquals(state, card.getState(), rating + " at " + at);
        assertEquals(stability, card.getStability(), EPSILON, rating + " at " + at);
        assertEquals(difficulty, card.getDifficulty(), EPSILON, rating + " at " + at);
        assertEquals(at, card.getLastReviewedAt());
        if (state == CardState.REVIEW) {
            assertEquals(at.toLocalDate().plusDays(card.getIntervalDays()).atTime(4, 0), card.getNextReviewAt());
        }
    }

    private static void assertStep(CardScheduler.Outcome outcome, CardState state, int step, LocalDateTime due) {
        assertEquals(state, outcome.state());
        assertEquals(step, outcome.learningStep());
        assertEquals(due, outcome.due());
        assertEquals(0, outcome.intervalDays());
    }

    /** Within the reference scheduler's fuzz range around {@code days}. */
    private static void assertInFuzzRange(int days, int actual) {
        double delta = 1.0 + 0.15 * Math.max(Math.min(days, 7) - 2.5, 0) + 0.1 * Math.max(Math.min(days, 20) - 7, 0)
            + 0.05 * Math.max(days - 20, 0);
        assertTrue(actual >= Math.round(days - delta) && actual <= Math.round(days + delta),
            actual + " is not within the fuzz range of " + days);
    }

    private static WordCard newCard(long id) {
        WordCard card = WordCard.createNew(1, "word" + id, "词");
        card.setId(id);
        card.setAddedAt(MONDAY.minusDays(1));
        return card;
    }

    private static WordCard reviewCard(long id, double stability, double difficulty, LocalDateTime lastReview) {
        WordCard card = newCard(id);
        card.setState(CardState.REVIEW);
        card.setStability(stability);
        card.setDifficulty(difficulty);
        card.setRepetitions(3);
        card.setConsecutiveCorrect(3);
        card.setLastReviewedAt(lastReview);
        card.setNextReviewAt(lastReview.plusDays(Math.round(stability)));
        return card;
    }
}
