package com.vocabtrainer.service.scheduling;

import java.time.Duration;
import java.util.List;

/**
 * How cards are scheduled.
 *
 * @param desiredRetention the chance of recall review intervals aim for (default 0.9)
 * @param dayRolloverHour  the hour a study day starts (default 4 am), see {@link StudyDay}
 * @param learningSteps    the delays between the first reviews of a new card (default 1 and 10 minutes)
 * @param relearningSteps  the delays between reviews after a lapse (default 10 minutes)
 * @param maximumInterval  the longest interval in days (default 36500)
 * @param learnAhead       how early a learning card is shown when nothing else is due (default 20 minutes)
 */
public record SchedulingOptions(
    double desiredRetention,
    int dayRolloverHour,
    List<Duration> learningSteps,
    List<Duration> relearningSteps,
    int maximumInterval,
    Duration learnAhead
) {
    public static final List<Duration> DEFAULT_LEARNING_STEPS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(10));
    public static final List<Duration> DEFAULT_RELEARNING_STEPS = List.of(Duration.ofMinutes(10));
    public static final Duration DEFAULT_LEARN_AHEAD = Duration.ofMinutes(20);
    /** The desired retention a setting may choose; outside this range intervals become useless. */
    public static final double MIN_DESIRED_RETENTION = 0.7;
    public static final double MAX_DESIRED_RETENTION = 0.97;

    public SchedulingOptions {
        if (!(desiredRetention >= MIN_DESIRED_RETENTION && desiredRetention <= MAX_DESIRED_RETENTION)) {
            throw new IllegalArgumentException("Desired retention must be from " + MIN_DESIRED_RETENTION + " to "
                + MAX_DESIRED_RETENTION + ": " + desiredRetention);
        }
        new StudyDay(dayRolloverHour);
        learningSteps = steps(learningSteps, "learning");
        relearningSteps = steps(relearningSteps, "relearning");
        if (maximumInterval < 1) {
            throw new IllegalArgumentException("Maximum interval must be at least 1 day: " + maximumInterval);
        }
        if (learnAhead == null || learnAhead.isNegative()) {
            throw new IllegalArgumentException("Learn-ahead must not be negative: " + learnAhead);
        }
    }

    public static SchedulingOptions defaults() {
        return new SchedulingOptions(Fsrs.DEFAULT_DESIRED_RETENTION, StudyDay.DEFAULT_ROLLOVER_HOUR,
            DEFAULT_LEARNING_STEPS, DEFAULT_RELEARNING_STEPS, Fsrs.DEFAULT_MAXIMUM_INTERVAL, DEFAULT_LEARN_AHEAD);
    }

    public SchedulingOptions withDesiredRetention(double retention) {
        return new SchedulingOptions(retention, dayRolloverHour, learningSteps, relearningSteps, maximumInterval,
            learnAhead);
    }

    public SchedulingOptions withDayRolloverHour(int hour) {
        return new SchedulingOptions(desiredRetention, hour, learningSteps, relearningSteps, maximumInterval,
            learnAhead);
    }

    public SchedulingOptions withMaximumInterval(int days) {
        return new SchedulingOptions(desiredRetention, dayRolloverHour, learningSteps, relearningSteps, days,
            learnAhead);
    }

    /**
     * The same options without learning and relearning steps: every rating moves the card into
     * review, as the SM-2 scheduler of older versions did.
     */
    public SchedulingOptions withoutSteps() {
        return new SchedulingOptions(desiredRetention, dayRolloverHour, List.of(), List.of(), maximumInterval,
            learnAhead);
    }

    public StudyDay studyDay() {
        return new StudyDay(dayRolloverHour);
    }

    private static List<Duration> steps(List<Duration> steps, String kind) {
        List<Duration> copy = List.copyOf(steps == null ? List.of() : steps);
        for (Duration step : copy) {
            if (step.isNegative() || step.isZero() || step.compareTo(Duration.ofDays(1)) >= 0) {
                throw new IllegalArgumentException("A " + kind + " step must be longer than 0 and shorter than a day: " + step);
            }
        }
        return copy;
    }
}
