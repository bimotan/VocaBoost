package com.vocabtrainer.service;

import com.vocabtrainer.util.Messages;

import java.util.Locale;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The language of the app, kept in the {@code settings} table ({@code ui.language}: {@code auto},
 * {@code zh_CN} or {@code en}; Auto until changed). Auto follows the computer's language: Simplified
 * Chinese for any Chinese locale, English for every other. The language is read at startup, so a
 * new choice applies when the app starts again. A saved value that is not one of these is logged and
 * Auto is used.
 */
public class LanguageSettings {
    public static final String LANGUAGE_KEY = "ui.language";

    /** A language the Settings tab offers. */
    public enum Language {
        AUTO("auto"),
        SIMPLIFIED_CHINESE("zh_CN"),
        ENGLISH("en");

        private final String settingValue;

        Language(String settingValue) {
            this.settingValue = settingValue;
        }

        /** What the {@code settings} table holds for it. */
        public String settingValue() {
            return settingValue;
        }

        /** The locale the app's texts are shown in; Auto follows {@code systemLocale}. */
        public Locale locale(Locale systemLocale) {
            return switch (this) {
                case AUTO -> Messages.isChinese(systemLocale) ? Messages.SIMPLIFIED_CHINESE : Messages.ENGLISH;
                case SIMPLIFIED_CHINESE -> Messages.SIMPLIFIED_CHINESE;
                case ENGLISH -> Messages.ENGLISH;
            };
        }

        static Language fromSettingValue(String value) {
            for (Language language : values()) {
                if (language.settingValue.equals(value)) {
                    return language;
                }
            }
            throw new IllegalArgumentException("Not a language setting: " + value);
        }
    }

    private static final Logger LOGGER = Logger.getLogger(LanguageSettings.class.getName());

    private final SettingsService settings;

    public LanguageSettings(SettingsService settings) {
        this.settings = settings;
    }

    /**
     * The saved language. It never fails: when the setting cannot be read or is not valid, the app
     * follows the computer's language.
     */
    public Language language() {
        String saved;
        try {
            saved = settings.get(LANGUAGE_KEY).map(String::trim).orElse("");
        } catch (IllegalStateException e) {
            LOGGER.log(Level.WARNING, "Cannot read the language; following the system language", e);
            return Language.AUTO;
        }
        if (saved.isEmpty()) {
            return Language.AUTO;
        }
        try {
            return Language.fromSettingValue(saved);
        } catch (IllegalArgumentException e) {
            LOGGER.warning("Ignoring setting " + LANGUAGE_KEY + "=" + saved + ": not auto, zh_CN or en");
            return Language.AUTO;
        }
    }

    /** Saves the language the app starts in from now on. */
    public void saveLanguage(Language language) {
        settings.save(LANGUAGE_KEY, Objects.requireNonNull(language, "language").settingValue());
    }
}
