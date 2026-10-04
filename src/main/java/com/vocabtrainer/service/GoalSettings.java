package com.vocabtrainer.service;

import com.vocabtrainer.domain.GoalTargets;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import static com.vocabtrainer.util.Messages.tr;

/**
 * The goals the user set, kept in the {@code settings} table: the default daily goals of every deck
 * ({@code goals.reviewsPerDay}, {@code goals.newWordsPerDay}), the goals of a deck that has its own
 * ({@code goals.reviewsPerDay.<deckId>}, {@code goals.newWordsPerDay.<deckId>}) and the session goal,
 * which is the review session size the Review tab starts with ({@link ReviewSettings#sessionSize()}).
 * The session size is the same for every deck: a deck switch keeps the size the user chose.
 * A saved value that is not valid is logged and the default is used.
 */
public class GoalSettings {
    static final String REVIEW_GOAL_KEY = "goals.reviewsPerDay";
    static final String NEW_WORD_GOAL_KEY = "goals.newWordsPerDay";

    private static final Logger LOGGER = Logger.getLogger(GoalSettings.class.getName());
    private static final GoalTargets BUILT_IN = new GoalTargets(GoalService.DEFAULT_REVIEW_GOAL,
        GoalService.DEFAULT_NEW_WORD_GOAL);

    /** Null when the settings are only kept in memory. */
    private final SettingsService settings;
    private final ReviewSettings reviewSettings;
    private final Map<String, String> inMemory = new HashMap<>();

    public GoalSettings(SettingsService settings, ReviewSettings reviewSettings) {
        this.settings = settings;
        this.reviewSettings = reviewSettings;
    }

    /** Goals kept in memory only, starting with the built-in defaults; for tests and tools. */
    public static GoalSettings inMemory() {
        return new GoalSettings(null, null);
    }

    /** The goals of every deck that has none of its own; 20 reviews and 5 new words until changed. */
    public GoalTargets defaults() {
        return read(REVIEW_GOAL_KEY, NEW_WORD_GOAL_KEY).orElse(BUILT_IN);
    }

    /** The goals the deck has of its own; empty when it uses the defaults. */
    public Optional<GoalTargets> deckGoals(long deckId) {
        return read(REVIEW_GOAL_KEY + "." + deckId, NEW_WORD_GOAL_KEY + "." + deckId);
    }

    /** The goals the deck works towards: its own, or else the defaults. */
    public GoalTargets goalsFor(long deckId) {
        return deckGoals(deckId).orElseGet(this::defaults);
    }

    public void saveDefaults(GoalTargets goals) {
        write(REVIEW_GOAL_KEY, NEW_WORD_GOAL_KEY, goals);
    }

    public void saveDeckGoals(long deckId, GoalTargets goals) {
        write(REVIEW_GOAL_KEY + "." + deckId, NEW_WORD_GOAL_KEY + "." + deckId, goals);
    }

    /** The deck goes back to the default goals. */
    public void clearDeckGoals(long deckId) {
        delete(REVIEW_GOAL_KEY + "." + deckId);
        delete(NEW_WORD_GOAL_KEY + "." + deckId);
    }

    /** The cards a review session aims for: a number of different cards, or 0 for All Due. */
    public int sessionGoal() {
        if (reviewSettings == null) {
            return Optional.ofNullable(inMemory.get(ReviewSettings.SESSION_SIZE_KEY))
                .map(Integer::parseInt)
                .orElse(ReviewSettings.DEFAULT_SESSION_SIZE);
        }
        return reviewSettings.sessionSize();
    }

    /** @throws IllegalArgumentException if {@code size} is not from 0 (All Due) to {@value ReviewSettings#MAX_SESSION_SIZE} */
    public void saveSessionGoal(int size) {
        if (reviewSettings == null) {
            if (size < 0 || size > ReviewSettings.MAX_SESSION_SIZE) {
                throw new IllegalArgumentException(tr("validation.sessionSize", ReviewSettings.MAX_SESSION_SIZE));
            }
            inMemory.put(ReviewSettings.SESSION_SIZE_KEY, String.valueOf(size));
            return;
        }
        reviewSettings.saveSessionSize(size);
    }

    /**
     * The most new words the deck introduces per study day (the Review tab's limit), so at most that
     * many can count towards its new-word goal on a day.
     */
    public int newCardsPerDay(long deckId) {
        return reviewSettings == null ? ReviewSettings.DEFAULT_NEW_CARDS_PER_DAY : reviewSettings.newCardsPerDay(deckId);
    }

    private Optional<GoalTargets> read(String reviewKey, String newWordKey) {
        Optional<String> reviews = get(reviewKey);
        Optional<String> newWords = get(newWordKey);
        if (reviews.isEmpty() && newWords.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new GoalTargets(Integer.parseInt(reviews.orElse("").trim()),
                Integer.parseInt(newWords.orElse("").trim())));
        } catch (IllegalArgumentException e) {
            LOGGER.warning("Ignoring goal settings " + reviewKey + "=" + reviews.orElse("") + ", "
                + newWordKey + "=" + newWords.orElse("") + ": not two numbers from 0 to " + GoalTargets.MAX_GOAL);
            return Optional.empty();
        }
    }

    private void write(String reviewKey, String newWordKey, GoalTargets goals) {
        put(reviewKey, String.valueOf(goals.reviewGoal()));
        put(newWordKey, String.valueOf(goals.newWordGoal()));
    }

    private Optional<String> get(String key) {
        Optional<String> value = settings == null ? Optional.ofNullable(inMemory.get(key)) : settings.get(key);
        return value.filter(text -> !text.isBlank());
    }

    private void put(String key, String value) {
        if (settings == null) {
            inMemory.put(key, value);
        } else {
            settings.save(key, value);
        }
    }

    private void delete(String key) {
        if (settings == null) {
            inMemory.remove(key);
        } else {
            settings.delete(key);
        }
    }
}
