package com.vocabtrainer.service;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * Rules for the API keys of the AI provider and the dictionary API. A key is only ever sent in a
 * request header, never shown in full, logged or put into an error message, and never sent
 * unencrypted to another computer.
 */
public final class ApiKeys {
    /** The hosts plain http may send a key to: this computer, where local AI servers listen. */
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1");
    /** Shorter keys show no characters at all, since a few would give away too much of them. */
    private static final int MIN_LENGTH_FOR_HINT = 16;
    private static final String MASK = "••••";

    private ApiKeys() {
    }

    /** How a saved key is shown: "••••" and its last four characters, or only "••••" for a short key. */
    public static String hint(String key) {
        String clean = key == null ? "" : key.strip();
        return clean.length() >= MIN_LENGTH_FOR_HINT ? MASK + clean.substring(clean.length() - 4) : MASK;
    }

    /** Whether {@code key} can go into an HTTP header: visible ASCII characters only, no spaces. */
    public static boolean isSendable(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        return key.chars().allMatch(c -> c > 0x20 && c < 0x7f);
    }

    /**
     * @param what names the key in the message, e.g. "The AI API key"
     * @throws IllegalArgumentException if the key cannot be sent; the message does not contain the key
     */
    static void requireSendable(String key, String what) {
        if (!isSendable(key)) {
            throw new IllegalArgumentException(what + " contains spaces or characters that cannot be sent"
                + " in an HTTP header (for example a full-width character or a line break); paste it again.");
        }
    }

    /** Whether a request to {@code uri} keeps a key from other computers: https, or http to this computer. */
    public static boolean isSafeToSendKey(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        return scheme.equals("https") || scheme.equals("http") && isLocalHost(uri.getHost());
    }

    /**
     * @param what names the URL in the message, e.g. "The AI base URL"
     * @throws IllegalArgumentException if a key sent to {@code uri} would cross the network unencrypted
     */
    static void requireSafeToSendKey(URI uri, String what) {
        if (!isSafeToSendKey(uri)) {
            throw new IllegalArgumentException(what + " uses plain http, which would send the API key unencrypted."
                + " Use https, or http only for a server on this computer (localhost, 127.0.0.1 or ::1).");
        }
    }

    static boolean isLocalHost(String host) {
        if (host == null) {
            return false;
        }
        String clean = host.toLowerCase(Locale.ROOT);
        if (clean.startsWith("[") && clean.endsWith("]")) {
            clean = clean.substring(1, clean.length() - 1);
        }
        return LOCAL_HOSTS.contains(clean);
    }

    /** {@code text} with every occurrence of {@code key} replaced by "***", e.g. a provider's error that echoes it. */
    static String redact(String text, String key) {
        if (text == null || key == null || key.isBlank()) {
            return text;
        }
        return text.replace(key.strip(), "***");
    }
}
