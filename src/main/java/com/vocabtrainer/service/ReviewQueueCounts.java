package com.vocabtrainer.service;

import com.vocabtrainer.repository.ReviewLogRepository;
import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.service.scheduling.StudyDay;

import java.sql.SQLException;
import java.time.LocalDateTime;

/**
 * What a deck has to study today: the due reviews and the new cards the new-cards-per-day limit
 * still lets in.
 *
 * @param learningDue             learning and relearning cards whose step time has come
 * @param reviewsDue              review cards due today
 * @param newCardsDue             new cards due today, before the limit
 * @param newCardsPerDay          the deck's new-cards-per-day limit
 * @param newCardsIntroducedToday new cards that had their first review this study day
 */
public record ReviewQueueCounts(
    int learningDue,
    int reviewsDue,
    int newCardsDue,
    int newCardsPerDay,
    int newCardsIntroducedToday
) {
    /** Learning, relearning and review cards that are due. */
    public int dueReviews() {
        return learningDue + reviewsDue;
    }

    /** The new cards still introduced today: what is left of the limit, at most the new cards there are. */
    public int newAvailableToday() {
        return Math.min(newCardsDue, Math.max(0, newCardsPerDay - newCardsIntroducedToday));
    }

    /** The due reviews and the new cards available today: what an All Due session shows. */
    public int dueToday() {
        return dueReviews() + newAvailableToday();
    }

    /** The counts of the deck at {@code now}; new cards introduced count from the start of the study day. */
    static ReviewQueueCounts read(WordRepository words, ReviewLogRepository logs, StudyDay studyDay, long deckId,
                                  LocalDateTime now, int newCardsPerDay) throws SQLException {
        WordRepository.DueCounts due = words.countDueByState(deckId, now, studyDay.end(now));
        int introduced = logs.countNewCardsIntroducedSince(deckId, studyDay.start(studyDay.of(now)));
        return new ReviewQueueCounts(due.learning(), due.review(), due.newCards(), newCardsPerDay, introduced);
    }
}
