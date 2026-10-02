package com.vocabtrainer.service;

import com.vocabtrainer.domain.ReviewLog;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.ReviewLogRepository;
import com.vocabtrainer.repository.WordRepository;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Gives words their FSRS card state when they have none: words from before schema version 5,
 * words an older version of the app added since, and words restored from a backup written before
 * FSRS. A word with review logs gets the state its history leads to, replayed rating by rating
 * (see {@link ReviewScheduler#replay}); a word without logs is estimated from its SM-2 schedule
 * (see {@link WordCard#estimateStateFromLegacySchedule}).
 */
public class CardStateBackfill {
    private static final Logger LOGGER = Logger.getLogger(CardStateBackfill.class.getName());

    private final WordRepository wordRepository;
    private final ReviewLogRepository reviewLogRepository;
    private final ReviewScheduler scheduler;

    public CardStateBackfill(WordRepository wordRepository, ReviewLogRepository reviewLogRepository,
                             ReviewScheduler scheduler) {
        this.wordRepository = wordRepository;
        this.reviewLogRepository = reviewLogRepository;
        this.scheduler = scheduler;
    }

    /**
     * Derives and saves the state of every word stored without one, in one transaction; the app runs
     * it at startup, right after the schema migration. Returns how many words it derived.
     */
    public int run() throws SQLException {
        return wordRepository.transactions().inTransaction(() -> {
            List<WordCard> words = wordRepository.findWithoutCardState();
            if (words.isEmpty()) {
                return 0;
            }
            Map<Long, List<ReviewLog>> histories = new HashMap<>();
            for (ReviewLog log : reviewLogRepository.findOfWordsWithoutCardState()) {
                histories.computeIfAbsent(log.getWordId(), id -> new ArrayList<>()).add(log);
            }
            int replayed = 0;
            for (WordCard word : words) {
                List<ReviewLog> history = histories.getOrDefault(word.getId(), List.of());
                if (derive(word, history)) {
                    replayed++;
                }
                wordRepository.update(word);
            }
            LOGGER.info("Derived the FSRS state of " + words.size() + " word(s): " + replayed
                + " replayed from their review logs, " + (words.size() - replayed) + " estimated from the SM-2 schedule");
            return words.size();
        });
    }

    /**
     * Derives the state of {@code word} from its review logs in the database, or from its SM-2
     * schedule when it has none, and saves it; joins the caller's transaction.
     */
    public void deriveAndSave(WordCard word) throws SQLException {
        derive(word, reviewLogRepository.findByWord(word.getId()));
        wordRepository.update(word);
    }

    /** Returns whether there was a history to replay. */
    private boolean derive(WordCard word, List<ReviewLog> history) {
        if (history.isEmpty()) {
            word.estimateStateFromLegacySchedule();
            // The SM-2 lapse count is kept, so a word that lapsed often is a leech like a replayed one.
            ReviewScheduler.tagIfLeech(word);
            return false;
        }
        scheduler.replay(word, history);
        return true;
    }
}
