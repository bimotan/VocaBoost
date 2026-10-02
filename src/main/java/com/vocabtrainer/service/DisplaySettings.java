package com.vocabtrainer.service;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * How the window shows text, kept in the {@code settings} table: the text size in percent of the
 * system's ({@code ui.textSize}: 100, 115 or 130; 100 until changed). A saved value that is not one
 * of these is logged and 100 is used.
 */
public class DisplaySettings {
    public static final String TEXT_SIZE_KEY = "ui.textSize";
    public static final int DEFAULT_TEXT_SIZE = 100;
    /** The text sizes the Settings tab offers, in percent of the system's text size. */
    public static final List<Integer> TEXT_SIZES = List.of(100, 115, 130);

    private static final Logger LOGGER = Logger.getLogger(DisplaySettings.class.getName());

    private final SettingsService settings;

    public DisplaySettings(SettingsService settings) {
        this.settings = settings;
    }

    /**
     * The saved text size, one of {@link #TEXT_SIZES}. It never fails: when the setting cannot be read
     * or is not valid, the window and its dialogs show text at 100%.
     */
    public int textSizePercent() {
        String saved;
        try {
            saved = settings.get(TEXT_SIZE_KEY).map(String::trim).orElse("");
        } catch (IllegalStateException e) {
            LOGGER.log(Level.WARNING, "Cannot read the text size; showing text at 100%", e);
            return DEFAULT_TEXT_SIZE;
        }
        if (saved.isEmpty()) {
            return DEFAULT_TEXT_SIZE;
        }
        try {
            int percent = Integer.parseInt(saved);
            if (TEXT_SIZES.contains(percent)) {
                return percent;
            }
        } catch (NumberFormatException e) {
            // Logged below.
        }
        LOGGER.warning("Ignoring setting " + TEXT_SIZE_KEY + "=" + saved + ": not one of " + TEXT_SIZES);
        return DEFAULT_TEXT_SIZE;
    }

    /**
     * Saves the text size.
     *
     * @throws IllegalArgumentException if {@code percent} is not one of {@link #TEXT_SIZES}
     */
    public void saveTextSizePercent(int percent) {
        if (!TEXT_SIZES.contains(percent)) {
            throw new IllegalArgumentException("Text size must be one of " + TEXT_SIZES + " percent.");
        }
        settings.save(TEXT_SIZE_KEY, String.valueOf(percent));
    }
}
