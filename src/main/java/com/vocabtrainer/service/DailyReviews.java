package com.vocabtrainer.service;

import com.vocabtrainer.repository.ReviewLogRepository;
import com.vocabtrainer.repository.ReviewLogRepository.DailyCount;
import com.vocabtrainer.service.scheduling.StudyDay;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The reviews of study days, read from the review logs. The dashboard, the daily goal, the
 * statistics and the report all count through here, so they show the same numbers for a day:
 * study days start at the rollover hour, practice is not a review, and a review is correct when it
 * did not count as Again ({@code ReviewLog.isCorrect()}).
 */
final class DailyReviews {
    private DailyReviews() {
    }

    /** The deck's reviews on {@code day}; a {@code deckId} of 0 or less counts every deck. */
    static DailyCount on(ReviewLogRepository logs, StudyDay studyDay, long deckId, LocalDate day) throws SQLException {
        return between(logs, studyDay, deckId, day, day).get(0);
    }

    /** One count per day from {@code first} to {@code last}, oldest first, days without reviews included. */
    static List<DailyCount> between(ReviewLogRepository logs, StudyDay studyDay, long deckId, LocalDate first,
                                    LocalDate last) throws SQLException {
        List<DailyCount> stored = logs.dailyCounts(deckId, studyDay.start(first), studyDay.start(last.plusDays(1)),
            studyDay.rolloverHour());
        List<DailyCount> days = new ArrayList<>();
        int next = 0;
        for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
            if (next < stored.size() && stored.get(next).day().equals(day)) {
                days.add(stored.get(next++));
            } else {
                days.add(DailyCount.none(day));
            }
        }
        return days;
    }
}
