package com.vocabtrainer.ui;

import com.vocabtrainer.domain.Achievement;
import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.service.scheduling.IntervalPreview;
import com.vocabtrainer.util.DateTimeUtil;
import com.vocabtrainer.util.Messages;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static com.vocabtrainer.util.Messages.tr;

/** Text formatting shared by the views; free of JavaFX so presenters can use it too. */
public final class Formats {
    private Formats() {
    }

    /** A day of the year as a chart's axis shows it: "11/16". */
    public static String shortDate(LocalDate date) {
        return date.getMonthValue() + "/" + date.getDayOfMonth();
    }

    /** A date with its day of the week, e.g. "Mon 2026-11-16" ("2026-11-16 周一" in Chinese). */
    public static String weekdayDate(LocalDate date) {
        return DateTimeFormatter.ofPattern(tr("format.weekdayDate"), Messages.locale()).format(date);
    }

    /** A short date with its day of the week, as a chart's axis shows it, e.g. "Mon 11/16". */
    public static String weekdayShortDate(LocalDate date) {
        return DateTimeFormatter.ofPattern(tr("format.weekdayShortDate"), Messages.locale()).format(date);
    }

    /** {@code value} (0 to 1) as a whole percentage, e.g. "87%". */
    public static String percent(double value) {
        return String.format("%.0f%%", value * 100);
    }

    /**
     * An interval as a rating button shows it: a learning step in minutes or hours ("1m", "6m",
     * "2h"), a review interval in days, months or years ("4d", "1.5mo", "2.1y"), followed by
     * " (exam)" when it was shortened so the card is reviewed before the exam ("12d (exam)").
     */
    public static String interval(IntervalPreview preview) {
        if (preview.isLearningStep()) {
            long minutes = Math.max(1L, Math.round(preview.learningDelay().toSeconds() / 60.0));
            return minutes < 60 ? tr("format.interval.minutes", minutes)
                : tr("format.interval.hours", Math.round(minutes / 60.0));
        }
        String interval = reviewInterval(preview.intervalDays());
        return preview.beforeExam() ? tr("format.interval.beforeExam", interval) : interval;
    }

    private static String reviewInterval(int days) {
        if (days < 30) {
            return tr("format.interval.days", days);
        }
        if (days < 365) {
            return tr("format.interval.months", String.format(Locale.ROOT, "%.1f", days / 30.0));
        }
        return tr("format.interval.years", String.format(Locale.ROOT, "%.1f", days / 365.0));
    }

    /** The Word List's interval: the review interval in days, "learning" during the steps, "-" for a new card. */
    public static String cardInterval(WordCard word) {
        CardState state = word.getState();
        if (state == CardState.NEW) {
            return "-";
        }
        return state.isLearning() ? tr("format.interval.learning") : DateTimeUtil.days(word.getIntervalDays());
    }

    /** The names of {@code achievements}, or "None yet". */
    public static String achievementNames(List<Achievement> achievements) {
        if (achievements == null || achievements.isEmpty()) {
            return tr("format.achievements.none");
        }
        return achievements.stream().map(Achievement::name).collect(Collectors.joining(tr("format.listSeparator")));
    }

    /** A new line listing the newly unlocked achievements, or nothing when there are none. */
    public static String unlockedSuffix(List<Achievement> achievements) {
        if (achievements == null || achievements.isEmpty()) {
            return "";
        }
        return System.lineSeparator() + tr("format.achievements.unlocked", achievementNames(achievements));
    }
}
