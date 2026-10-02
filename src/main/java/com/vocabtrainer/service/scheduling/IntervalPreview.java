package com.vocabtrainer.service.scheduling;

import java.time.Duration;

/**
 * When a rating would bring a card back: after a learning step of minutes, or after a number of
 * study days.
 *
 * @param learningDelay the delay of a learning or relearning step; zero for an interval of days
 * @param intervalDays  the interval in study days; zero for a learning step
 */
public record IntervalPreview(Duration learningDelay, int intervalDays) {
    public IntervalPreview {
        if (learningDelay == null || learningDelay.isNegative() || intervalDays < 0) {
            throw new IllegalArgumentException("Invalid interval: " + learningDelay + ", " + intervalDays + " days");
        }
    }

    public static IntervalPreview step(Duration delay) {
        return new IntervalPreview(delay, 0);
    }

    public static IntervalPreview days(int days) {
        return new IntervalPreview(Duration.ZERO, days);
    }

    public boolean isLearningStep() {
        return intervalDays == 0;
    }
}
