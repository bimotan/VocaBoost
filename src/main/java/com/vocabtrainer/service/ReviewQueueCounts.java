package com.vocabtrainer.service;

import com.vocabtrainer.repository.ReviewLogRepository;
import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.service.scheduling.StudyDay;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

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

    /**
     * The counts of several decks together: the sums, except that the new cards available today are
     * those each deck's own limit allows, so {@link #newAvailableToday()} is their sum too.
     */
    static ReviewQueueCounts sum(List<ReviewQueueCounts> decks) {
        if (decks.size() == 1) {
            return decks.get(0);
        }
        int learning = 0;
        int reviews = 0;
        int newCards = 0;
        int available = 0;
        for (ReviewQueueCounts deck : decks) {
            learning += deck.learningDue();
            reviews += deck.reviewsDue();
            newCards += deck.newCardsDue();
            available += deck.newAvailableToday();
        }
        // As if one deck had a limit of exactly what the decks still let in today.
        return new ReviewQueueCounts(learning, reviews, newCards, available, 0);
    }

    /** The counts of the deck at {@code now}; new cards introduced count from the start of the study day. */
    static ReviewQueueCounts read(WordRepository words, ReviewLogRepository logs, StudyDay studyDay, long deckId,
                                  LocalDateTime now, int newCardsPerDay) throws SQLException {
        WordRepository.DueCounts due = words.countDueByState(deckId, now, studyDay.end(now));
        int introduced = logs.countNewCardsIntroducedSince(deckId, studyDay.start(studyDay.of(now)));
        return new ReviewQueueCounts(due.learning(), due.review(), due.newCards(), newCardsPerDay, introduced);
    }
}
