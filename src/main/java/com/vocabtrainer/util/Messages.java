package com.vocabtrainer.util;

import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The text the user sees, in the language of the app: English or Simplified Chinese. The texts are
 * in the resource bundle {@value #BUNDLE}: {@code messages.properties} (English, the base bundle)
 * and {@code messages_zh_CN.properties}, both UTF-8.
 *
 * <p>Every value is a {@link MessageFormat} pattern, also one without arguments, so an apostrophe
 * is always written twice ({@code Don''t}) and {@code {0}} is the first argument. Numbers are
 * formatted for the language ({@code 1,240}); English plurals use choice formats
 * ({@code {0,choice,1#word|1<words}}). The views and the services both use it; it has no JavaFX
 * dependency. Log messages are not translated.
 *
 * <p>The language is set once at startup ({@link #setLocale}), before the window is built; until
 * then, and in unit tests, it is English.
 */
public final class Messages {
    /** The base name of the bundle. */
    public static final String BUNDLE = "com.vocabtrainer.i18n.messages";
    /** The languages the app is translated into: English and Simplified Chinese. */
    public static final Locale ENGLISH = Locale.ENGLISH;
    public static final Locale SIMPLIFIED_CHINESE = Locale.SIMPLIFIED_CHINESE;

    private static final Logger LOGGER = Logger.getLogger(Messages.class.getName());
    /**
     * Without the default locale as a fallback: English must come from the base bundle even when the
     * computer's locale is Chinese.
     */
    private static final ResourceBundle.Control CONTROL =
        ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

    private static volatile Locale locale = ENGLISH;

    private Messages() {
    }

    /** Shows every text from now on in {@code locale}: Simplified Chinese for {@code zh_CN}, otherwise English. */
    public static void setLocale(Locale locale) {
        Messages.locale = isChinese(Objects.requireNonNull(locale, "locale")) ? SIMPLIFIED_CHINESE : ENGLISH;
    }

    /** The language the texts are shown in: {@link #ENGLISH} or {@link #SIMPLIFIED_CHINESE}. */
    public static Locale locale() {
        return locale;
    }

    /** Whether the texts are shown in Chinese. */
    public static boolean isChinese() {
        return isChinese(locale);
    }

    /** Whether {@code locale} is a Chinese locale, which the app shows in Simplified Chinese. */
    public static boolean isChinese(Locale locale) {
        return locale != null && "zh".equals(locale.getLanguage());
    }

    /** The text of {@code key} in the app's language, with {@code args} filled in. */
    public static String tr(String key, Object... args) {
        return trIn(locale, key, args);
    }

    /**
     * Sentences one after the other, as a status line or a result shows them: separated by a space in
     * English, without one in Chinese. Empty parts are left out.
     */
    public static String sentences(List<String> sentences) {
        String text = "";
        for (String sentence : sentences) {
            if (sentence == null || sentence.isEmpty()) {
                continue;
            }
            text = text.isEmpty() ? sentence : tr("format.sentences", text, sentence);
        }
        return text;
    }

    /**
     * The text of {@code key} in {@code locale}'s language, e.g. to say something in the language the
     * user just chose. A key that is missing is logged and shown as it is.
     */
    public static String trIn(Locale locale, String key, Object... args) {
        Locale language = isChinese(locale) ? SIMPLIFIED_CHINESE : ENGLISH;
        String pattern;
        try {
            pattern = ResourceBundle.getBundle(BUNDLE, language, CONTROL).getString(key);
        } catch (MissingResourceException e) {
            LOGGER.log(Level.WARNING, "No text for the message key " + key, e);
            return key;
        }
        return new MessageFormat(pattern, language).format(args == null ? new Object[0] : args);
    }
}
