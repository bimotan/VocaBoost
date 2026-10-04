package com.vocabtrainer.service;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

import static com.vocabtrainer.util.Messages.tr;

/**
 * When the Review tab asks the configured AI provider to explain a checked answer without the user
 * pressing Explain. Saved as {@link SettingsService#AI_AUTO_EXPLAIN_KEY}; {@link #AFTER_MISTAKE} until
 * changed. Offline mode wins over every choice: while it is on, nothing is sent.
 */
public enum AutoExplain {
    /** After every checked answer. */
    ALWAYS("always"),
    /**
     * Only when the answer check counts the answer as less than a match: it caps the rating at Again,
     * Hard or Good (a wrong answer, a misspelling, a partly right meaning).
     */
    AFTER_MISTAKE("mistakes"),
    /** Never; the user asks with Explain. */
    ON_DEMAND("never");

    /** The choice until the user makes one. */
    public static final AutoExplain DEFAULT = AFTER_MISTAKE;

    private final String settingValue;

    AutoExplain(String settingValue) {
        this.settingValue = settingValue;
    }

    /** The value saved in the settings table: "always", "mistakes" or "never". */
    public String settingValue() {
        return settingValue;
    }

    /** The choice saved as {@code value}, ignoring case and spaces; empty for anything else. */
    public static Optional<AutoExplain> fromSetting(String value) {
        String clean = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(choice -> choice.settingValue.equals(clean)).findFirst();
    }

    /**
     * Whether an answer is explained without being asked for.
     *
     * @param mistake whether the answer check capped the answer's rating
     */
    public boolean explainsAutomatically(boolean mistake) {
        return this == ALWAYS || this == AFTER_MISTAKE && mistake;
    }

    /** How the AI settings name the choice. */
    public String label() {
        return switch (this) {
            case ALWAYS -> tr("ai.autoExplain.always");
            case AFTER_MISTAKE -> tr("ai.autoExplain.mistakes");
            case ON_DEMAND -> tr("ai.autoExplain.never");
        };
    }
}
