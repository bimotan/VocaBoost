package com.vocabtrainer.service;

import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.ReviewLog;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.service.scheduling.IntervalPreview;
import com.vocabtrainer.service.scheduling.SchedulingOptions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewSchedulerTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 5, 28, 9, 0);

    private final ReviewScheduler scheduler = new ReviewScheduler(new Random(1));

    @Test
    void againOnAReviewCardIsALapseThatComesBackInTenMinutes() {
        WordCard word = reviewCard(1, 7, 5, NOW.minusDays(7));
        word.setConsecutiveCorrect(2);

        scheduler.applyRating(word, ReviewRating.AGAIN, 1.0, NOW);

        assertEquals(CardState.RELEARNING, word.getState());
        assertEquals(0, word.getConsecutiveCorrect());
        assertEquals(1, word.getLapses());
        assertEquals(NOW.plusMinutes(10), word.getNextReviewAt());
        assertTrue(word.getStability() < 7);
    }

    @Test
    void lowSimilarityTurnsGoodIntoAgain() {
        WordCard word = reviewCard(2, 7, 5, NOW.minusDays(7));

        scheduler.applyRating(word, ReviewRating.GOOD, 0.2, NOW);

        assertEquals(CardState.RELEARNING, word.getState());
        assertEquals(0, word.getConsecutiveCorrect());
        assertEquals(1, word.getLapses());
        assertTrue(word.getEasinessFactor() < WordCard.DEFAULT_EASINESS, "the old easiness factor follows the difficulty");
    }

    @Test
    void similarityCapsTheRating() {
        assertEquals(ReviewRating.EASY, ReviewScheduler.effectiveRating(ReviewRating.EASY, 0.95));
        assertEquals(ReviewRating.GOOD, ReviewScheduler.effectiveRating(ReviewRating.EASY, 0.8));
        assertEquals(ReviewRating.HARD, ReviewScheduler.effectiveRating(ReviewRating.GOOD, 0.6));
        assertEquals(ReviewRating.AGAIN, ReviewScheduler.effectiveRating(ReviewRating.EASY, 0.5));
        assertEquals(ReviewRating.HARD, ReviewScheduler.effectiveRating(ReviewRating.HARD, 1.0));
        assertEquals(ReviewRating.AGAIN, ReviewScheduler.effectiveRating(ReviewRating.GOOD, Double.NaN));
    }

    @Test
    void thePreviewShowsWhatEachRatingWouldDoWithoutChangingTheCard() {
        WordCard word = WordCard.createNew(1, "lucid", "清晰的", NOW);
        word.setId(3);

        Map<ReviewRating, IntervalPreview> correct = scheduler.preview(word, 1.0, NOW);

        assertEquals(IntervalPreview.step(Duration.ofMinutes(1)), correct.get(ReviewRating.AGAIN));
        assertEquals(IntervalPreview.step(Duration.ofSeconds(330)), correct.get(ReviewRating.HARD));
        assertEquals(IntervalPreview.step(Duration.ofMinutes(10)), correct.get(ReviewRating.GOOD));
        int easyDays = correct.get(ReviewRating.EASY).intervalDays();
        assertTrue(easyDays >= 13 && easyDays <= 19, "easy: " + easyDays);
        assertEquals(CardState.NEW, word.getState());
        assertEquals(0, word.getRepetitions());

        // A wrong answer makes every rating count as Again.
        Map<ReviewRating, IntervalPreview> wrong = scheduler.preview(word, 0.1, NOW);
        for (ReviewRating rating : ReviewRating.values()) {
            assertEquals(IntervalPreview.step(Duration.ofMinutes(1)), wrong.get(rating), rating.name());
        }

        scheduler.applyRating(word, ReviewRating.EASY, 1.0, NOW);
        assertEquals(easyDays, word.getIntervalDays(), "the preview is what the rating does");
    }

    @Test
    void theEighthLapseTagsTheWordAsALeech() {
        WordCard word = reviewCard(4, 5, 8, NOW.minusDays(5));
        word.setTags("gre");
        word.setLapses(WordCard.LEECH_LAPSES - 1);

        assertFalse(scheduler.applyRating(word, ReviewRating.HARD, 1.0, NOW));
        assertFalse(word.isLeech());
        assertTrue(scheduler.applyRating(word, ReviewRating.AGAIN, 1.0, NOW.plusDays(3)));

        assertEquals(WordCard.LEECH_LAPSES, word.getLapses());
        assertTrue(word.isLeech());
        assertEquals("gre; leech", word.getTags());
        // Tagged once; later lapses do not repeat it.
        assertFalse(scheduler.applyRating(word, ReviewRating.AGAIN, 1.0, NOW.plusDays(4)));
        assertEquals("gre; leech", word.getTags());
    }

    /** Learning steps change when a card comes back, not its memory, so a replay ends where the live reviews did. */
    @Test
    void replayingTheReviewLogsRebuildsTheSchedule() {
        WordCard live = WordCard.createNew(1, "lucid", "清晰的", NOW);
        live.setId(5);
        List<ReviewLog> history = new ArrayList<>();
        Object[][] reviews = {
            {ReviewRating.GOOD, 1.0, NOW}, {ReviewRating.GOOD, 1.0, NOW.plusMinutes(10)},
            {ReviewRating.GOOD, 0.2, NOW.plusDays(4)}, {ReviewRating.GOOD, 1.0, NOW.plusDays(4).plusMinutes(10)},
            {ReviewRating.EASY, 0.95, NOW.plusDays(9)}
        };
        for (Object[] review : reviews) {
            ReviewRating rating = (ReviewRating) review[0];
            double similarity = (double) review[1];
            LocalDateTime at = (LocalDateTime) review[2];
            scheduler.applyRating(live, rating, similarity, at);
            history.add(new ReviewLog(history.size() + 1, 5, at, "", "清晰的", similarity, rating, 1000));
        }

        WordCard replayed = WordCard.createNew(1, "lucid", "清晰的", NOW);
        replayed.setId(5);
        replayed.setRepetitions(17);
        replayed.setLapses(3);
        // Logs in any order are replayed oldest first.
        List<ReviewLog> newestFirst = new ArrayList<>(history);
        Collections.reverse(newestFirst);
        scheduler.replay(replayed, newestFirst);

        assertEquals(live.getState(), replayed.getState());
        assertEquals(live.getStability(), replayed.getStability(), 1e-9);
        assertEquals(live.getDifficulty(), replayed.getDifficulty(), 1e-9);
        assertEquals(live.getNextReviewAt(), replayed.getNextReviewAt());
        assertEquals(live.getIntervalDays(), replayed.getIntervalDays());
        assertEquals(5, replayed.getRepetitions());
        assertEquals(1, replayed.getLapses());
        assertEquals(2, replayed.getConsecutiveCorrect());
    }

    @Test
    void aReplayedHistoryHasNoLearningStepsLikeTheOldSchedulerThatRecordedIt() {
        // Failed and recalled in turn, four days apart: before learning steps, every review moved
        // the card into review, so each failure after the first review is a lapse.
        LocalDateTime start = LocalDateTime.of(2026, 8, 23, 9, 0);
        List<ReviewLog> history = new ArrayList<>();
        for (int k = 0; k < 17; k++) {
            boolean failed = k % 2 == 0;
            history.add(new ReviewLog(k + 1, 8, start.plusDays(4L * k), "", "挑剔", failed ? 0.1 : 1.0,
                failed ? ReviewRating.AGAIN : ReviewRating.GOOD, 1000));
        }
        WordCard word = WordCard.createNew(1, "cavil", "挑剔", NOW);
        word.setId(8);

        scheduler.replay(word, history.subList(0, 9));

        // py-fsrs 5.1.3 without learning steps gives the same memory.
        assertEquals(CardState.REVIEW, word.getState());
        assertEquals(1.2259, word.getStability(), 1e-4);
        assertEquals(9.3112, word.getDifficulty(), 1e-4);
        assertEquals(4, word.getLapses());
        assertEquals(LocalDateTime.of(2026, 9, 25, 4, 0), word.getNextReviewAt());
        assertFalse(word.isLeech());

        scheduler.replay(word, history);

        assertEquals(8, word.getLapses());
        assertTrue(word.isLeech(), "the replay tags leeches too");
    }

    @Test
    void retrievabilityIsEmptyForANewWordAndFallsWithTime() {
        assertTrue(ReviewScheduler.retrievability(WordCard.createNew(1, "new", "新", NOW), NOW).isEmpty());

        WordCard word = reviewCard(6, 120, 5, NOW.minusDays(3));
        // Review finding A8: the old hours-based strength put this card at 3%.
        assertTrue(ReviewScheduler.retrievability(word, NOW).getAsDouble() > 0.99);
        assertEquals(0.9, ReviewScheduler.retrievability(word, NOW.plusDays(117)).getAsDouble(), 1e-9);
    }

    @Test
    void theOptionsDecideRetentionAndTheDayRollover() {
        ReviewScheduler strict = new ReviewScheduler(SchedulingOptions.defaults().withDesiredRetention(0.95)
            .withDayRolloverHour(0));
        WordCard word = reviewCard(7, 30, 5, NOW.minusDays(30));

        int defaultDays = scheduler.preview(word, 1.0, NOW).get(ReviewRating.GOOD).intervalDays();
        int strictDays = strict.preview(word, 1.0, NOW).get(ReviewRating.GOOD).intervalDays();

        assertTrue(strictDays < defaultDays, strictDays + " vs " + defaultDays);
        assertEquals(0, strict.studyDay().rolloverHour());
    }

    private static WordCard reviewCard(long id, double stability, double difficulty, LocalDateTime lastReview) {
        WordCard card = WordCard.createNew(1, "word" + id, "词", NOW);
        card.setId(id);
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
