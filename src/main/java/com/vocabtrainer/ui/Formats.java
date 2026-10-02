package com.vocabtrainer.ui;

import com.vocabtrainer.domain.Achievement;
import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.service.scheduling.IntervalPreview;
import com.vocabtrainer.util.DateTimeUtil;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Text formatting shared by the views; free of JavaFX so presenters can use it too. */
public final class Formats {
    private Formats() {
    }

    public static String percent(double value) {
        return String.format("%.0f%%", value * 100);
    }

    /**
     * An interval as a rating button shows it: a learning step in minutes or hours ("1m", "6m",
     * "2h"), a review interval in days, months or years ("4d", "1.5mo", "2.1y").
     */
    public static String interval(IntervalPreview preview) {
        if (preview.isLearningStep()) {
            long minutes = Math.max(1L, Math.round(preview.learningDelay().toSeconds() / 60.0));
            return minutes < 60 ? minutes + "m" : Math.round(minutes / 60.0) + "h";
        }
        int days = preview.intervalDays();
        if (days < 30) {
            return days + "d";
        }
        if (days < 365) {
            return String.format(Locale.ROOT, "%.1fmo", days / 30.0);
        }
        return String.format(Locale.ROOT, "%.1fy", days / 365.0);
    }

    /** The Word List's interval: the review interval in days, "learning" during the steps, "-" for a new card. */
    public static String cardInterval(WordCard word) {
        CardState state = word.getState();
        if (state == CardState.NEW) {
            return "-";
        }
        return state.isLearning() ? "learning" : DateTimeUtil.days(word.getIntervalDays());
    }

    /** The names of {@code achievements}, or "None yet". */
    public static String achievementNames(List<Achievement> achievements) {
        if (achievements == null || achievements.isEmpty()) {
            return "None yet";
        }
        return achievements.stream().map(Achievement::name).collect(Collectors.joining(", "));
    }

    /** A new line listing the newly unlocked achievements, or nothing when there are none. */
    public static String unlockedSuffix(List<Achievement> achievements) {
        if (achievements == null || achievements.isEmpty()) {
            return "";
        }
        return System.lineSeparator() + "Unlocked: " + achievementNames(achievements);
    }
}
