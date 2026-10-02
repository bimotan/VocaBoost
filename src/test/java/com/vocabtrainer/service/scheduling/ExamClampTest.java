package com.vocabtrainer.service.scheduling;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The arithmetic that keeps reviews before an exam (review finding G5). */
class ExamClampTest {
    private static final double[] FRACTIONS = {0.0, 0.2, 0.45, 0.7, 0.999};

    @Test
    void theLeadIsATenthOfTheIntervalFromOneToSevenDays() {
        assertEquals(1, ExamClamp.lead(1, 0.0));
        assertEquals(1, ExamClamp.lead(14, 0.0));
        assertEquals(2, ExamClamp.lead(15, 0.0));
        assertEquals(3, ExamClamp.lead(30, 0.0));
        assertEquals(7, ExamClamp.lead(70, 0.0));
        assertEquals(7, ExamClamp.lead(3650, 0.0), "never more than the final week");
    }

    @Test
    void longIntervalsSpreadOverTheFinalWeekAndALongerIntervalNeverComesLater() {
        assertEquals(4, ExamClamp.lead(3650, 0.999), "up to three days less");
        assertEquals(1, ExamClamp.lead(5, 0.999), "a short interval stays the day before");
        assertEquals(1, ExamClamp.lead(20, 0.999));
        assertEquals(2, ExamClamp.lead(20, 0.0));
        for (double fraction : FRACTIONS) {
            int previous = 1;
            for (int interval = 1; interval <= 400; interval++) {
                int lead = ExamClamp.lead(interval, fraction);
                assertTrue(lead >= 1 && lead <= ExamClamp.FINAL_DAYS, interval + "d: " + lead);
                assertTrue(lead >= previous, "the lead of " + interval + "d at " + fraction + " shrank");
                previous = lead;
            }
        }
        assertEquals(7, ExamClamp.lead(70, -1.0), "fractions outside 0 to 1 are clamped");
        assertEquals(4, ExamClamp.lead(70, 1.0));
    }

    @Test
    void anExamThatIsOverTodayOrTomorrowChangesNothing() {
        assertEquals(30, ExamClamp.interval(30, -5, 3), "the exam is over");
        assertEquals(30, ExamClamp.interval(30, 0, 3), "the exam is today");
        assertEquals(30, ExamClamp.interval(30, 1, 3), "the exam is tomorrow: today's review is the last one");
        assertEquals(1, ExamClamp.interval(1, 1, 1));
    }

    @Test
    void aReviewOnOrAfterTheExamDayComesItsLeadBeforeIt() {
        assertEquals(2, ExamClamp.interval(5, 3, 1), "due the day before the exam");
        assertEquals(2, ExamClamp.interval(3, 3, 1), "due on the exam day itself counts as after it");
        assertEquals(93, ExamClamp.interval(300, 100, 7), "a week before a far exam");
        assertEquals(3, ExamClamp.interval(40, 7, 4));
        assertEquals(1, ExamClamp.interval(40, 2, 1), "tomorrow when that is the day before the exam");
    }

    @Test
    void aReviewBeforeTheExamDayOrWithinTheLeadStays() {
        assertEquals(10, ExamClamp.interval(10, 11, 1), "due the day before the exam anyway");
        assertEquals(10, ExamClamp.interval(10, 40, 1));
        assertEquals(60, ExamClamp.interval(60, 5, 6), "reviewed within the final days: this was the last one");
        assertEquals(60, ExamClamp.interval(60, 6, 6));
    }

    /** Whatever the interval and the distance, a card gets a review in the last week before the exam, never after it. */
    @Test
    void everyCardIsReviewedInTheLastWeekBeforeTheExam() {
        for (double fraction : FRACTIONS) {
            for (int interval = 1; interval <= 400; interval++) {
                int lead = ExamClamp.lead(interval, fraction);
                for (long daysToExam = -3; daysToExam <= 150; daysToExam++) {
                    int clamped = ExamClamp.interval(interval, daysToExam, lead);
                    String context = interval + "d, exam in " + daysToExam + "d, lead " + lead + ": " + clamped;
                    assertTrue(clamped >= 1 && clamped <= interval, context);
                    if (daysToExam <= 0 || interval < daysToExam) {
                        assertEquals(interval, clamped, context);
                    } else if (clamped == interval) {
                        assertTrue(daysToExam <= lead && lead <= ExamClamp.FINAL_DAYS,
                            "kept only when today is in the final week: " + context);
                    } else {
                        long beforeExam = daysToExam - clamped;
                        assertTrue(beforeExam >= 1 && beforeExam <= ExamClamp.FINAL_DAYS, context);
                    }
                }
            }
        }
    }

    @Test
    void aCardAlreadyScheduledAfterTheExamIsBroughtForward() {
        assertEquals(23, ExamClamp.rescheduleIn(30, 60, 50, 7), "a week before the exam");
        assertEquals(0, ExamClamp.rescheduleIn(3, 60, 50, 7), "today when the final week has started");
        assertEquals(-1, ExamClamp.rescheduleIn(30, 29, 50, 7), "due before the exam day");
        assertEquals(29, ExamClamp.rescheduleIn(30, 30, 50, 1), "due on the exam day");
        assertEquals(-1, ExamClamp.rescheduleIn(3, 60, 5, 7), "last reviewed within its lead of the exam");
        assertEquals(-1, ExamClamp.rescheduleIn(0, 60, 50, 7), "the exam is today");
        assertEquals(-1, ExamClamp.rescheduleIn(-2, 60, 50, 7), "the exam is over");
    }
}
