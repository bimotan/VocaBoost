package com.vocabtrainer.service;

import com.vocabtrainer.domain.Exam;

import static com.vocabtrainer.util.Messages.tr;

/**
 * How far away the deck's exam is.
 *
 * @param exam     the exam
 * @param daysLeft study days from today to the exam day: 0 on the day itself, negative once it is over
 */
public record ExamCountdown(Exam exam, long daysLeft) {
    public boolean isOver() {
        return daysLeft < 0;
    }

    /** "GRE in 45 days", "GRE is tomorrow", "GRE is today" or "GRE was 3 days ago". */
    public String toDisplayText() {
        String name = exam.name();
        if (daysLeft > 1) {
            return tr("exam.countdown.inDays", name, daysLeft);
        }
        if (daysLeft == 1) {
            return tr("exam.countdown.tomorrow", name);
        }
        if (daysLeft == 0) {
            return tr("exam.countdown.today", name);
        }
        return daysLeft == -1 ? tr("exam.countdown.yesterday", name) : tr("exam.countdown.daysAgo", name, -daysLeft);
    }
}
