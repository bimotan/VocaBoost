package com.vocabtrainer.service.scheduling;

import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CardScheduler} with an exam day: reviews that would fall on or after it come in the last
 * days before it, the rating previews say so, and the FSRS memory is what it is without the exam.
 */
class ExamSchedulingTest {
    private static final LocalDateTime MONDAY = LocalDateTime.of(2026, 3, 2, 9, 0);
    private static final LocalDate TODAY = MONDAY.toLocalDate();

    private final CardScheduler scheduler = new CardScheduler(SchedulingOptions.defaults());

    @Test
    void aReviewThatWouldFallAfterTheExamComesInTheFinalDaysWithTheSameMemory() {
        // A mature card: Good would give about 50 days.
        WordCard card = reviewCard(3, 40, 5, MONDAY.minusDays(40));
        LocalDate exam = TODAY.plusDays(20);

        Map<ReviewRating, CardScheduler.Outcome> free = scheduler.outcomes(card, MONDAY);
        Map<ReviewRating, CardScheduler.Outcome> beforeExam = scheduler.outcomes(card, MONDAY, exam);

        for (ReviewRating rating : new ReviewRating[] {ReviewRating.HARD, ReviewRating.GOOD, ReviewRating.EASY}) {
            CardScheduler.Outcome unclamped = free.get(rating);
            CardScheduler.Outcome clamped = beforeExam.get(rating);
            assertTrue(unclamped.intervalDays() >= 20, rating + " would reach the exam: " + unclamped.intervalDays());
            long daysBeforeExam = 20 - clamped.intervalDays();
            assertTrue(daysBeforeExam >= 1 && daysBeforeExam <= ExamClamp.FINAL_DAYS, rating + ": " + clamped);
            assertEquals(TODAY.plusDays(clamped.intervalDays()).atTime(4, 0), clamped.due());
            assertTrue(clamped.beforeExam());
            assertTrue(clamped.preview(MONDAY).beforeExam(), "the rating button says so");
            assertEquals(clamped.intervalDays(), clamped.preview(MONDAY).intervalDays());
            // Only the due date moves: the memory is what FSRS computed.
            assertEquals(unclamped.stability(), clamped.stability());
            assertEquals(unclamped.difficulty(), clamped.difficulty());
            assertEquals(CardState.REVIEW, clamped.state());
        }
        assertTrue(beforeExam.get(ReviewRating.HARD).intervalDays() <= beforeExam.get(ReviewRating.GOOD).intervalDays());
        assertTrue(beforeExam.get(ReviewRating.GOOD).intervalDays() <= beforeExam.get(ReviewRating.EASY).intervalDays());
        // Again goes to relearning, a step of minutes, which the exam does not change.
        assertEquals(free.get(ReviewRating.AGAIN), beforeExam.get(ReviewRating.AGAIN));
    }

    @Test
    void applyingARatingSchedulesTheClampedDueDate() {
        WordCard card = reviewCard(5, 40, 5, MONDAY.minusDays(40));
        LocalDate exam = TODAY.plusDays(20);
        CardScheduler.Outcome preview = scheduler.outcome(card, ReviewRating.GOOD, MONDAY, exam);

        CardScheduler.Outcome applied = scheduler.apply(card, ReviewRating.GOOD, MONDAY, exam);

        assertEquals(preview, applied, "the button showed what the rating does");
        assertEquals(preview.due(), card.getNextReviewAt());
        assertEquals(preview.intervalDays(), card.getIntervalDays());
        assertTrue(card.getNextReviewAt().toLocalDate().isBefore(exam));
    }

    @Test
    void anExamThatIsOverTodayOrTomorrowLeavesTheScheduleAlone() {
        WordCard card = reviewCard(7, 40, 5, MONDAY.minusDays(40));
        Map<ReviewRating, CardScheduler.Outcome> free = scheduler.outcomes(card, MONDAY);

        assertEquals(free, scheduler.outcomes(card, MONDAY, TODAY.minusDays(3)), "over");
        assertEquals(free, scheduler.outcomes(card, MONDAY, TODAY), "today");
        assertEquals(free, scheduler.outcomes(card, MONDAY, TODAY.plusDays(1)), "tomorrow: this is the last review");
        assertEquals(free, scheduler.outcomes(card, MONDAY, null), "no exam");
        assertEquals(free, scheduler.outcomes(card, MONDAY, TODAY.plusDays(400)), "every interval ends before it");
    }

    @Test
    void theExamDayIsAStudyDay() {
        // A young card: Good gives a few days, so its last review is the day before the exam.
        WordCard card = reviewCard(9, 3, 5, MONDAY.minusDays(3));
        // 1 am on the 3rd still belongs to the 2nd: the exam on the 4th is two study days away.
        LocalDateTime lateNight = LocalDateTime.of(2026, 3, 3, 1, 0);
        assertTrue(scheduler.outcome(card, ReviewRating.GOOD, lateNight).intervalDays() >= 2);
        CardScheduler.Outcome good = scheduler.outcome(card, ReviewRating.GOOD, lateNight, TODAY.plusDays(2));

        assertEquals(1, good.intervalDays());
        assertEquals(LocalDateTime.of(2026, 3, 3, 4, 0), good.due(), "the start of the day before the exam");
    }

    @Test
    void newAndLearningCardsKeepTheirSteps() {
        WordCard card = WordCard.createNew(1, "word", "词");
        card.setId(11);
        LocalDate exam = TODAY.plusDays(5);
        Map<ReviewRating, CardScheduler.Outcome> free = scheduler.outcomes(card, MONDAY);
        Map<ReviewRating, CardScheduler.Outcome> beforeExam = scheduler.outcomes(card, MONDAY, exam);

        assertEquals(free.get(ReviewRating.AGAIN), beforeExam.get(ReviewRating.AGAIN));
        assertEquals(free.get(ReviewRating.HARD), beforeExam.get(ReviewRating.HARD));
        assertEquals(free.get(ReviewRating.GOOD), beforeExam.get(ReviewRating.GOOD));
        // Easy graduates with about two weeks, which would be after the exam.
        int easyInterval = free.get(ReviewRating.EASY).intervalDays();
        CardScheduler.Outcome easy = beforeExam.get(ReviewRating.EASY);
        assertEquals(5 - ExamClamp.lead(easyInterval, CardScheduler.fuzzFraction(card)), easy.intervalDays());
        assertTrue(easy.beforeExam());
        assertFalse(free.get(ReviewRating.EASY).beforeExam());
    }

    @Test
    void aCardScheduledAfterTheExamIsBroughtForwardUnlessItWasJustReviewed() {
        LocalDate exam = TODAY.plusDays(30);
        WordCard due = reviewCard(13, 60, 5, MONDAY.minusDays(10));
        due.setIntervalDays(60);
        due.setNextReviewAt(MONDAY.minusDays(10).toLocalDate().plusDays(60).atTime(4, 0));

        Optional<LocalDateTime> moved = scheduler.dueBeforeExam(due, MONDAY, exam);

        assertEquals(Optional.of(exam.minusDays(ExamClamp.lead(60, CardScheduler.fuzzFraction(due))).atTime(4, 0)),
            moved, "its lead before the exam, from the start of the study day");

        WordCard beforeTheExam = reviewCard(15, 10, 5, MONDAY.minusDays(5));
        beforeTheExam.setNextReviewAt(MONDAY.plusDays(5));
        assertTrue(scheduler.dueBeforeExam(beforeTheExam, MONDAY, exam).isEmpty());
        assertTrue(scheduler.dueBeforeExam(due, MONDAY, null).isEmpty());
        assertTrue(scheduler.dueBeforeExam(due, MONDAY, TODAY).isEmpty(), "the exam is today");

        // Reviewed yesterday, three days before the exam, with an interval of 60 days (a lead of 3 to
        // 6 days): that was its last review.
        WordCard justReviewed = reviewCard(17, 60, 5, MONDAY.minusDays(1));
        justReviewed.setIntervalDays(60);
        assertTrue(scheduler.dueBeforeExam(justReviewed, MONDAY, TODAY.plusDays(2)).isEmpty());

        WordCard learning = reviewCard(19, 60, 5, MONDAY.minusDays(10));
        learning.setState(CardState.RELEARNING);
        assertTrue(scheduler.dueBeforeExam(learning, MONDAY, exam).isEmpty(), "only review cards");
    }

    private static WordCard reviewCard(long id, double stability, double difficulty, LocalDateTime lastReview) {
        WordCard card = WordCard.createNew(1, "word" + id, "词");
        card.setId(id);
        card.setAddedAt(lastReview.minusDays(1));
        card.setState(CardState.REVIEW);
        card.setStability(stability);
        card.setDifficulty(difficulty);
        card.setRepetitions(3);
        card.setConsecutiveCorrect(3);
        card.setIntervalDays((int) Math.round(stability));
        card.setLastReviewedAt(lastReview);
        card.setNextReviewAt(lastReview.plusDays(Math.round(stability)));
        return card;
    }
}
