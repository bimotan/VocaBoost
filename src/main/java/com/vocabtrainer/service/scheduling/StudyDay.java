package com.vocabtrainer.service.scheduling;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Study days start at a rollover hour (4 am by default) instead of midnight, like Anki's: a review at
 * 1 am still belongs to the evening before. Intervals in days count study days, so a card reviewed
 * at 21:30 with a 1-day interval is due from the next study day's start (4:00 the next morning),
 * not 24 hours later, and "due today" means due before the next rollover.
 */
public final class StudyDay {
    public static final int DEFAULT_ROLLOVER_HOUR = 4;

    private final int rolloverHour;

    public StudyDay() {
        this(DEFAULT_ROLLOVER_HOUR);
    }

    /** @param rolloverHour the hour (0 to 23) at which a new study day starts */
    public StudyDay(int rolloverHour) {
        if (rolloverHour < 0 || rolloverHour > 23) {
            throw new IllegalArgumentException("The day rollover hour must be from 0 to 23: " + rolloverHour);
        }
        this.rolloverHour = rolloverHour;
    }

    public int rolloverHour() {
        return rolloverHour;
    }

    /** The study day {@code time} belongs to. */
    public LocalDate of(LocalDateTime time) {
        return time.minusHours(rolloverHour).toLocalDate();
    }

    /** When {@code day} starts. */
    public LocalDateTime start(LocalDate day) {
        return day.atTime(rolloverHour, 0);
    }

    /** The next rollover after {@code now}: cards due before it are due today. */
    public LocalDateTime end(LocalDateTime now) {
        return start(of(now).plusDays(1));
    }

    /** Whole study days from the day of {@code from} to the day of {@code to}; negative if {@code to} is earlier. */
    public long daysBetween(LocalDateTime from, LocalDateTime to) {
        return ChronoUnit.DAYS.between(of(from), of(to));
    }

    /** The start of the study day {@code days} days after the day of {@code time}. */
    public LocalDateTime startOfDayAfter(LocalDateTime time, int days) {
        return start(of(time).plusDays(days));
    }

    @Override
    public String toString() {
        return "StudyDay[rollover " + rolloverHour + ":00]";
    }
}
