package com.vocabtrainer.ui;

import com.vocabtrainer.domain.Achievement;

import java.util.List;
import java.util.stream.Collectors;

/** Text formatting shared by the views; free of JavaFX so presenters can use it too. */
public final class Formats {
    private Formats() {
    }

    public static String percent(double value) {
        return String.format("%.0f%%", value * 100);
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
