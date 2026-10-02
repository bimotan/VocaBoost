package com.vocabtrainer.service;

import com.vocabtrainer.domain.ReviewMode;

import java.util.Optional;
import java.util.logging.Logger;

/**
 * The review settings kept in the {@code settings} table: each deck's new-cards-per-day limit
 * ({@code review.newCardsPerDay.<deckId>}) and the session size and mode the user last chose
 * ({@code review.sessionSize}, {@code review.mode}), which the Review tab starts with. A saved value
 * that is not valid is logged and the default is used.
 */
public class ReviewSettings {
    /** How many new cards a deck introduces per study day unless its setting says otherwise. */
    public static final int DEFAULT_NEW_CARDS_PER_DAY = 20;
    public static final int MAX_NEW_CARDS_PER_DAY = 9999;
    /** The session size before the user chose one: 20 different cards. */
    public static final int DEFAULT_SESSION_SIZE = 20;
    /** The largest session size; 0 means All Due. */
    public static final int MAX_SESSION_SIZE = 500;

    static final String NEW_CARDS_PER_DAY_KEY_PREFIX = "review.newCardsPerDay.";
    static final String SESSION_SIZE_KEY = "review.sessionSize";
    static final String MODE_KEY = "review.mode";

    private static final Logger LOGGER = Logger.getLogger(ReviewSettings.class.getName());

    private final SettingsService settings;

    public ReviewSettings(SettingsService settings) {
        this.settings = settings;
    }

    /** The most new cards the deck introduces per study day, from 0 to {@value #MAX_NEW_CARDS_PER_DAY}. */
    public int newCardsPerDay(long deckId) {
        return intSetting(NEW_CARDS_PER_DAY_KEY_PREFIX + deckId, DEFAULT_NEW_CARDS_PER_DAY, 0, MAX_NEW_CARDS_PER_DAY);
    }

    /** @throws IllegalArgumentException if {@code limit} is not from 0 to {@value #MAX_NEW_CARDS_PER_DAY} */
    public void saveNewCardsPerDay(long deckId, int limit) {
        if (limit < 0 || limit > MAX_NEW_CARDS_PER_DAY) {
            throw new IllegalArgumentException("New cards per day must be between 0 and " + MAX_NEW_CARDS_PER_DAY + ".");
        }
        settings.save(NEW_CARDS_PER_DAY_KEY_PREFIX + deckId, String.valueOf(limit));
    }

    /** The session size last chosen: a number of different cards, or 0 for All Due. */
    public int sessionSize() {
        return intSetting(SESSION_SIZE_KEY, DEFAULT_SESSION_SIZE, 0, MAX_SESSION_SIZE);
    }

    /** @throws IllegalArgumentException if {@code size} is not from 0 (All Due) to {@value #MAX_SESSION_SIZE} */
    public void saveSessionSize(int size) {
        if (size < 0 || size > MAX_SESSION_SIZE) {
            throw new IllegalArgumentException("Session size must be between 0 and " + MAX_SESSION_SIZE + ".");
        }
        settings.save(SESSION_SIZE_KEY, String.valueOf(size));
    }

    /** The review mode last chosen; English to Chinese before any was chosen. */
    public ReviewMode mode() {
        Optional<String> value = settings.get(MODE_KEY).filter(text -> !text.isBlank());
        if (value.isEmpty()) {
            return ReviewMode.EN_TO_ZH;
        }
        try {
            return ReviewMode.valueOf(value.get().trim());
        } catch (IllegalArgumentException e) {
            LOGGER.warning("Ignoring setting " + MODE_KEY + "=" + value.get() + ": not a review mode");
            return ReviewMode.EN_TO_ZH;
        }
    }

    public void saveMode(ReviewMode mode) {
        settings.save(MODE_KEY, (mode == null ? ReviewMode.EN_TO_ZH : mode).name());
    }

    private int intSetting(String key, int defaultValue, int min, int max) {
        Optional<String> value = settings.get(key).filter(text -> !text.isBlank());
        if (value.isEmpty()) {
            return defaultValue;
        }
        try {
            int number = Integer.parseInt(value.get().trim());
            if (number >= min && number <= max) {
                return number;
            }
        } catch (NumberFormatException e) {
            // Logged below.
        }
        LOGGER.warning("Ignoring setting " + key + "=" + value.get() + ": not a number from " + min + " to " + max);
        return defaultValue;
    }
}
