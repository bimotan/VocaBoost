package com.vocabtrainer.service.scheduling;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StudyDayTest {
    private final StudyDay studyDay = new StudyDay();
    private final LocalDate march10 = LocalDate.of(2026, 3, 10);

    @Test
    void aNewStudyDayStartsAtFourInTheMorning() {
        assertEquals(4, studyDay.rolloverHour());
        assertEquals(march10.minusDays(1), studyDay.of(march10.atTime(3, 59)));
        assertEquals(march10, studyDay.of(march10.atTime(4, 0)));
        assertEquals(march10, studyDay.of(march10.atTime(4, 1)));
        assertEquals(march10, studyDay.of(march10.plusDays(1).atTime(1, 30)), "after midnight is still the evening before");
    }

    @Test
    void todayEndsAtTheNextRollover() {
        assertEquals(march10.atTime(4, 0), studyDay.end(march10.atTime(3, 59)));
        assertEquals(march10.plusDays(1).atTime(4, 0), studyDay.end(march10.atTime(4, 1)));
        assertEquals(march10.plusDays(1).atTime(4, 0), studyDay.end(march10.atTime(23, 30)));
    }

    @Test
    void daysAreCountedInStudyDays() {
        LocalDateTime evening = march10.atTime(21, 30);

        assertEquals(1, studyDay.daysBetween(evening, march10.plusDays(1).atTime(7, 30)));
        assertEquals(0, studyDay.daysBetween(evening, march10.plusDays(1).atTime(3, 0)));
        assertEquals(-1, studyDay.daysBetween(march10.plusDays(1).atTime(7, 30), evening));
        // A 1-day interval from the evening is due when the next study day starts, not 24 hours later.
        assertEquals(march10.plusDays(1).atTime(4, 0), studyDay.startOfDayAfter(evening, 1));
    }

    @Test
    void theRolloverHourIsConfigurable() {
        StudyDay midnight = new StudyDay(0);
        assertEquals(march10, midnight.of(march10.atTime(0, 1)));
        assertEquals(march10.plusDays(1).atStartOfDay(), midnight.end(march10.atTime(23, 59)));
        assertThrows(IllegalArgumentException.class, () -> new StudyDay(24));
        assertThrows(IllegalArgumentException.class, () -> new StudyDay(-1));
    }
}
