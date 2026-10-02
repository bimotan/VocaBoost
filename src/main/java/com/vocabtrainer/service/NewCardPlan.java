package com.vocabtrainer.service;

import java.util.Locale;

/**
 * How many new words a day the deck must introduce to start every new word before the exam.
 *
 * <p>The new-cards-per-day limit counts the new words introduced since the start of the study day,
 * so a limit of X lets in X minus those already introduced today, then X on each later day before
 * the exam. {@link #wordsPerDay} is the smallest limit whose days add up to every new word.
 *
 * @param newWords        the deck's words that were never reviewed
 * @param introducedToday new words the deck already introduced this study day
 * @param daysLeft        study days before the exam, today included (the exam day is not)
 * @param currentLimit    the deck's new-cards-per-day limit now
 * @param wordsPerDay     the smallest limit that introduces every new word before the exam; 0 when
 *                        there are none
 */
public record NewCardPlan(int newWords, int introducedToday, long daysLeft, int currentLimit, int wordsPerDay) {
    public NewCardPlan {
        if (newWords < 0 || introducedToday < 0 || daysLeft < 1 || currentLimit < 0 || wordsPerDay < 0) {
            throw new IllegalArgumentException("Invalid new-word plan: " + newWords + " words, " + introducedToday
                + " introduced, " + daysLeft + " days, limit " + currentLimit + ", " + wordsPerDay + " per day");
        }
    }

    /**
     * The plan for {@code newWords} words in {@code daysLeft} days (at least 1), today having
     * introduced {@code introducedToday} already.
     */
    public static NewCardPlan of(int newWords, int introducedToday, long daysLeft, int currentLimit) {
        return new NewCardPlan(newWords, introducedToday, daysLeft, currentLimit,
            wordsPerDay(newWords, introducedToday, daysLeft));
    }

    /**
     * The smallest limit X with max(0, X - introducedToday) + X * (daysLeft - 1) >= newWords: today
     * lets in what is left of X, every later day X.
     */
    static int wordsPerDay(int newWords, int introducedToday, long daysLeft) {
        if (daysLeft < 1) {
            throw new IllegalArgumentException("A new-word plan needs at least one day before the exam: " + daysLeft);
        }
        if (newWords <= 0) {
            return 0;
        }
        // A limit of at least introducedToday: X * daysLeft - introducedToday >= newWords.
        long atLeastToday = Math.max(introducedToday, ceilDiv(newWords + (long) introducedToday, daysLeft));
        if (daysLeft > 1) {
            // A smaller limit lets nothing more in today: X * (daysLeft - 1) >= newWords.
            long laterDaysOnly = ceilDiv(newWords, daysLeft - 1);
            if (laterDaysOnly < introducedToday) {
                return (int) Math.min(Integer.MAX_VALUE, Math.min(atLeastToday, laterDaysOnly));
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, atLeastToday);
    }

    /** Every new word of the deck has been introduced. */
    public boolean isDone() {
        return newWords == 0;
    }

    /** The deck's limit already introduces every new word in time. */
    public boolean isOnTrack() {
        return currentLimit >= wordsPerDay;
    }

    /** The limit the plan would set: {@link #wordsPerDay}, at most {@link ReviewSettings#MAX_NEW_CARDS_PER_DAY}. */
    public int limitToApply() {
        return Math.min(wordsPerDay, ReviewSettings.MAX_NEW_CARDS_PER_DAY);
    }

    /**
     * For example "To finish 1,240 new words before the exam you need ~28 new words/day (now 20)."
     */
    public String toDisplayText() {
        if (isDone()) {
            return "Every new word of this deck has been started.";
        }
        String perDay = String.format(Locale.ROOT, "To finish %,d new %s before the exam you need ~%,d new %s/day",
            newWords, newWords == 1 ? "word" : "words", wordsPerDay, wordsPerDay == 1 ? "word" : "words");
        return perDay + (isOnTrack()
            ? String.format(Locale.ROOT, "; the limit of %,d/day is enough.", currentLimit)
            : String.format(Locale.ROOT, " (now %,d).", currentLimit));
    }

    private static long ceilDiv(long dividend, long divisor) {
        return -Math.floorDiv(-dividend, divisor);
    }
}
