package com.vocabtrainer.service;

import com.vocabtrainer.domain.Exam;

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
            return name + " in " + daysLeft + " days";
        }
        if (daysLeft == 1) {
            return name + " is tomorrow";
        }
        if (daysLeft == 0) {
            return name + " is today";
        }
        return name + " was " + (daysLeft == -1 ? "yesterday" : -daysLeft + " days ago");
    }
}
