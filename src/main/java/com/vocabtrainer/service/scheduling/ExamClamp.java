package com.vocabtrainer.service.scheduling;

/**
 * Brings reviews that would fall on or after an exam forward, so every card gets its last review in
 * the final days before the exam instead of the day after it. Only the due date moves: the card's
 * memory (stability and difficulty) is what FSRS computed, and a review a few days early barely
 * changes it.
 *
 * <p>The last review comes a <i>lead</i> of days before the exam: a tenth of the interval FSRS chose,
 * at least 1 and at most {@value #FINAL_DAYS} days. A card with a long interval keeps a high chance
 * of recall for a week, so it can come earlier, while a card with a short one comes the day before.
 * Cards with long intervals spread over up to {@value #MAX_SPREAD} earlier days, chosen by the card's
 * fuzz like its intervals, so they do not all fall on one day. The arithmetic counts study days.
 */
public final class ExamClamp {
    /** A clamped card's last review before the exam is within this many days of it. */
    public static final int FINAL_DAYS = 7;
    /** The lead is the interval divided by this, rounded. */
    static final int INTERVAL_PER_LEAD_DAY = 10;
    /** How many days earlier than its lead a card's last review may come. */
    static final int MAX_SPREAD = 3;

    private ExamClamp() {
    }

    /**
     * How many days before the exam a card with {@code intervalDays} gets its last review: a tenth
     * of the interval from 1 to {@value #FINAL_DAYS}, minus up to {@value #MAX_SPREAD} days chosen by
     * {@code fraction} (from 0 to 1), never less than 1. A longer interval never gets a shorter lead
     * for the same fraction.
     */
    public static int lead(int intervalDays, double fraction) {
        int base = Math.max(1, Math.min(FINAL_DAYS, (int) Math.round(intervalDays / (double) INTERVAL_PER_LEAD_DAY)));
        int spread = Math.min(MAX_SPREAD, base - 1);
        double clampedFraction = Math.max(0.0, Math.min(fraction, Math.nextDown(1.0)));
        return base - (int) Math.floor(clampedFraction * (spread + 1));
    }

    /**
     * The interval a review today should get when the exam is {@code daysToExam} study days away
     * (0 for today, negative when it is over) and FSRS chose {@code intervalDays}:
     * <ul>
     *   <li>{@code intervalDays} when the exam is today or over, or the next review comes before the
     *       exam day anyway;</li>
     *   <li>{@code intervalDays} when today is already within the card's last {@code lead} days before
     *       the exam: this review is its last one (so with the exam tomorrow nothing changes);</li>
     *   <li>otherwise {@code daysToExam - lead}: the card is due {@code lead} days before the exam,
     *       which is at least tomorrow.</li>
     * </ul>
     */
    public static int interval(int intervalDays, long daysToExam, int lead) {
        if (daysToExam <= 0 || intervalDays < daysToExam || daysToExam <= lead) {
            return intervalDays;
        }
        return (int) (daysToExam - lead);
    }

    /**
     * When a card that is already scheduled on or after the exam should be due instead, in days from
     * today; -1 when it can stay. It stays when the exam is today or over, when it is due before the
     * exam day, or when its last review was already within its lead of the exam. Otherwise it is due
     * {@code lead} days before the exam, or today when that day has passed.
     *
     * @param dueInDays          days from today to the day it is due
     * @param reviewedDaysBefore days from its last review to the exam
     */
    public static int rescheduleIn(long daysToExam, long dueInDays, long reviewedDaysBefore, int lead) {
        if (daysToExam <= 0 || dueInDays < daysToExam || reviewedDaysBefore <= lead) {
            return -1;
        }
        return (int) Math.max(0, daysToExam - lead);
    }
}
