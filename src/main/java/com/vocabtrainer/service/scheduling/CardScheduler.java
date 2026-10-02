package com.vocabtrainer.service.scheduling;

import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Schedules one card with FSRS-5: the learning and relearning steps, the move to review, intervals
 * in study days with fuzz, and the card's memory from {@link Fsrs}. It works on a card's fields only
 * and changes nothing until {@link #apply} is called, so the same arithmetic gives the rating
 * buttons' previews, the real review and the replay of old review logs.
 *
 * <p>States follow the FSRS reference scheduler: a new card goes through the learning steps (Again
 * restarts them, Hard repeats the step, Good moves to the next or graduates, Easy graduates at
 * once); a review card rated Again lapses into the relearning steps. Elapsed time is counted in
 * whole study days, and a review on the same study day as the last one uses FSRS's short-term
 * stability, so early reviews barely change the schedule. Intervals of three days or more get a
 * fuzz of a few percent, derived from the card id and its review count so that it is reproducible,
 * and Hard, Good and Easy always give increasing intervals.
 */
public final class CardScheduler {
    /** Intervals shorter than this are not fuzzed. */
    static final int FUZZ_MIN_DAYS = 3;

    private final SchedulingOptions options;
    private final Fsrs fsrs;
    private final StudyDay studyDay;

    public CardScheduler(SchedulingOptions options) {
        this.options = options;
        this.fsrs = new Fsrs(options.desiredRetention(), options.maximumInterval());
        this.studyDay = options.studyDay();
    }

    public SchedulingOptions options() {
        return options;
    }

    public StudyDay studyDay() {
        return studyDay;
    }

    public Fsrs fsrs() {
        return fsrs;
    }

    /**
     * What rating a card at {@code now} with a rating would do.
     *
     * @param state         the card's state afterwards
     * @param learningStep  the learning or relearning step afterwards; 0 in review
     * @param intervalDays  the interval in study days when the card is in review afterwards, otherwise 0
     * @param due           when the card is due next
     * @param lapse         whether the rating is a lapse: a review card rated Again
     */
    public record Outcome(CardState state, double stability, double difficulty, int learningStep, int intervalDays,
                          LocalDateTime due, boolean lapse) {
        /** The interval a rating button shows. */
        public IntervalPreview preview(LocalDateTime now) {
            return state == CardState.REVIEW
                ? IntervalPreview.days(intervalDays)
                : IntervalPreview.step(Duration.between(now, due));
        }
    }

    /** What each rating would do to {@code card} at {@code now}; the card is not changed. */
    public Map<ReviewRating, Outcome> outcomes(WordCard card, LocalDateTime now) {
        int elapsedDays = card.getLastReviewedAt() == null
            ? 0
            : (int) Math.max(0L, Math.min(Integer.MAX_VALUE, studyDay.daysBetween(card.getLastReviewedAt(), now)));
        Fsrs.Memory before = card.getState() == CardState.NEW || !(card.getStability() > 0)
            ? null
            : new Fsrs.Memory(card.getStability(), card.getDifficulty() >= 1 ? card.getDifficulty()
                : WordCard.difficultyFromEasiness(card.getEasinessFactor()));
        double fuzz = fuzzFraction(card);

        Map<ReviewRating, Outcome> outcomes = new EnumMap<>(ReviewRating.class);
        // Hard, Good and Easy in this order, so each interval can be kept above the previous one.
        int minimumPassingInterval = 1;
        for (ReviewRating rating : ReviewRating.values()) {
            Fsrs.Memory after = fsrs.next(before, elapsedDays, rating.getGrade());
            Step step = transition(card, rating);
            boolean lapse = card.getState() == CardState.REVIEW && rating == ReviewRating.AGAIN;
            if (step.delay() != null) {
                outcomes.put(rating, new Outcome(step.state(), after.stability(), after.difficulty(), step.index(), 0,
                    now.plus(step.delay()), lapse));
                continue;
            }
            int minimum = rating == ReviewRating.AGAIN ? 1 : minimumPassingInterval;
            int days = fuzzedInterval(fsrs.nextInterval(after.stability()), fuzz, minimum, options.maximumInterval());
            if (rating != ReviewRating.AGAIN) {
                minimumPassingInterval = Math.min(days + 1, options.maximumInterval());
            }
            outcomes.put(rating, new Outcome(CardState.REVIEW, after.stability(), after.difficulty(), 0, days,
                studyDay.startOfDayAfter(now, days), lapse));
        }
        return outcomes;
    }

    public Outcome outcome(WordCard card, ReviewRating rating, LocalDateTime now) {
        return outcomes(card, now).get(rating);
    }

    /**
     * Rates {@code card} at {@code now}: its state, memory, due time and counters change as
     * {@link #outcome} says. The SM-2 fields of older versions are kept meaningful: the interval
     * is the scheduled interval (0 while learning) and the easiness factor follows the difficulty.
     */
    public Outcome apply(WordCard card, ReviewRating rating, LocalDateTime now) {
        Outcome outcome = outcome(card, rating, now);
        card.setState(outcome.state());
        card.setStability(outcome.stability());
        card.setDifficulty(outcome.difficulty());
        card.setLearningStep(outcome.learningStep());
        card.setIntervalDays(outcome.intervalDays());
        card.setNextReviewAt(outcome.due());
        card.setLastReviewedAt(now);
        card.setRepetitions(card.getRepetitions() + 1);
        if (outcome.lapse()) {
            card.setLapses(card.getLapses() + 1);
        }
        card.setConsecutiveCorrect(rating == ReviewRating.AGAIN ? 0 : card.getConsecutiveCorrect() + 1);
        card.setEasinessFactor(WordCard.easinessFromDifficulty(outcome.difficulty()));
        return outcome;
    }

    /**
     * Puts a card the user already knows straight into review, without a review: it gets
     * {@code stability} days of stability and the difficulty of a first Easy rating, and is due after
     * the interval that stability gives at the desired retention (with fuzz). The last review time is
     * {@code now}, so its memory decays from now on; the review counters stay as they are.
     */
    public Outcome markKnown(WordCard card, double stability, LocalDateTime now) {
        double difficulty = fsrs.initialDifficulty(ReviewRating.EASY.getGrade());
        int days = fuzzedInterval(fsrs.nextInterval(stability), fuzzFraction(card), 1, options.maximumInterval());
        Outcome outcome = new Outcome(CardState.REVIEW, stability, difficulty, 0, days,
            studyDay.startOfDayAfter(now, days), false);
        card.setState(outcome.state());
        card.setStability(outcome.stability());
        card.setDifficulty(outcome.difficulty());
        card.setLearningStep(0);
        card.setIntervalDays(days);
        card.setNextReviewAt(outcome.due());
        card.setLastReviewedAt(now);
        card.setEasinessFactor(WordCard.easinessFromDifficulty(difficulty));
        return outcome;
    }

    /** Where a rating moves the card: a step with its delay, or into review (delay null). */
    private record Step(CardState state, int index, Duration delay) {
        static final Step GRADUATE = new Step(CardState.REVIEW, 0, null);
    }

    private Step transition(WordCard card, ReviewRating rating) {
        return switch (card.getState()) {
            case NEW -> stepTransition(CardState.LEARNING, 0, options.learningSteps(), rating);
            case LEARNING -> stepTransition(CardState.LEARNING, card.getLearningStep(), options.learningSteps(), rating);
            case RELEARNING -> stepTransition(CardState.RELEARNING, card.getLearningStep(), options.relearningSteps(), rating);
            case REVIEW -> rating == ReviewRating.AGAIN && !options.relearningSteps().isEmpty()
                ? new Step(CardState.RELEARNING, 0, options.relearningSteps().get(0))
                : Step.GRADUATE;
        };
    }

    private static Step stepTransition(CardState state, int index, List<Duration> steps, ReviewRating rating) {
        int step = Math.max(0, index);
        if (steps.isEmpty() || (step >= steps.size() && rating != ReviewRating.AGAIN)) {
            // No steps, or more steps were done than there are now.
            return Step.GRADUATE;
        }
        return switch (rating) {
            case AGAIN -> new Step(state, 0, steps.get(0));
            case HARD -> new Step(state, step, hardDelay(steps, step));
            case GOOD -> step + 1 >= steps.size() ? Step.GRADUATE : new Step(state, step + 1, steps.get(step + 1));
            case EASY -> Step.GRADUATE;
        };
    }

    /** Hard repeats the step; on the first step it waits between the first and second step (1.5 times a single one). */
    private static Duration hardDelay(List<Duration> steps, int step) {
        if (step > 0) {
            return steps.get(step);
        }
        if (steps.size() == 1) {
            return steps.get(0).multipliedBy(3).dividedBy(2);
        }
        return steps.get(0).plus(steps.get(1)).dividedBy(2);
    }

    /**
     * The interval with fuzz: a value from a range of a few percent around it (about +-1 day at 3
     * days, +-15% up to a week, +-10% up to 20 days and +-5% beyond), chosen by {@code fraction}, and
     * at least {@code minimum}. Intervals shorter than {@value #FUZZ_MIN_DAYS} days are not fuzzed.
     */
    static int fuzzedInterval(int interval, double fraction, int minimum, int maximum) {
        int lowest = Math.min(Math.max(1, minimum), maximum);
        int days = Math.max(lowest, Math.min(interval, maximum));
        if (days < FUZZ_MIN_DAYS) {
            return days;
        }
        double delta = 1.0
            + 0.15 * Math.max(Math.min(days, 7) - 2.5, 0.0)
            + 0.10 * Math.max(Math.min(days, 20) - 7, 0.0)
            + 0.05 * Math.max(days - 20, 0.0);
        int lower = clamp((int) Math.max(2L, Math.round(days - delta)), lowest, maximum);
        int upper = clamp((int) Math.round(days + delta), lower, maximum);
        return Math.min(upper, lower + (int) Math.floor(fraction * (upper - lower + 1)));
    }

    /** The same card at the same review count always gets the same fuzz. */
    static double fuzzFraction(WordCard card) {
        return new Random(card.getId() * 0x9E3779B97F4A7C15L + card.getRepetitions()).nextDouble();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
