package com.vocabtrainer.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The two bundles of {@link Messages}: the same keys in the same order, every value a valid
 * {@link MessageFormat} pattern with the same arguments in both languages, and every key used by the
 * code, which uses no key the bundles lack.
 */
class MessagesBundleTest {
    private static final String ENGLISH = "/com/vocabtrainer/i18n/messages.properties";
    private static final String CHINESE = "/com/vocabtrainer/i18n/messages_zh_CN.properties";
    /** A key passed to {@code tr("key", ...)} or {@code trIn(locale, "key", ...)}. */
    private static final Pattern KEY_USE =
        Pattern.compile("(?:\\btr\\(\\s*|\\btrIn\\(\\s*[^,()\"]+,\\s*)\"([A-Za-z0-9_.]+)\"");
    /** {@code tr(} or {@code trIn(} followed by something other than a literal key. */
    private static final Pattern COMPUTED_KEY = Pattern.compile("\\btr\\(\\s*[^\"\\s)]|\\btrIn\\(\\s*[^,()\"]+,\\s*[^\"\\s]");
    private static final Pattern ARGUMENT = Pattern.compile("\\{(\\d+)");
    private static final Pattern HAN = Pattern.compile("\\p{IsHan}");

    @Test
    void bothLanguagesHaveTheSameKeysInTheSameOrder() throws IOException {
        Map<String, String> english = bundle(ENGLISH);
        Map<String, String> chinese = bundle(CHINESE);

        Set<String> onlyEnglish = new TreeSet<>(english.keySet());
        onlyEnglish.removeAll(chinese.keySet());
        Set<String> onlyChinese = new TreeSet<>(chinese.keySet());
        onlyChinese.removeAll(english.keySet());
        assertEquals(Set.of(), onlyEnglish, "keys missing from the Chinese bundle");
        assertEquals(Set.of(), onlyChinese, "keys missing from the English bundle");
        assertEquals(new ArrayList<>(english.keySet()), new ArrayList<>(chinese.keySet()), "the same order");
    }

    @Test
    void everyValueIsAPatternWithTheSameArgumentsInBothLanguages() throws IOException {
        Map<String, String> english = bundle(ENGLISH);
        Map<String, String> chinese = bundle(CHINESE);
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> entry : english.entrySet()) {
            String key = entry.getKey();
            String zh = chinese.getOrDefault(key, "");
            checkPattern(key, entry.getValue(), Locale.ENGLISH, problems);
            checkPattern(key, zh, Locale.SIMPLIFIED_CHINESE, problems);
            if (!arguments(entry.getValue()).equals(arguments(zh))) {
                problems.add(key + ": English uses " + arguments(entry.getValue()) + ", Chinese " + arguments(zh));
            }
        }
        assertEquals(List.of(), problems);
    }

    @Test
    void theChineseBundleIsTranslated() throws IOException {
        Map<String, String> english = bundle(ENGLISH);
        Map<String, String> chinese = bundle(CHINESE);
        List<String> untranslated = new ArrayList<>();
        for (Map.Entry<String, String> entry : chinese.entrySet()) {
            String zh = entry.getValue();
            // Values that are only placeholders, numbers, names or punctuation read the same in both languages.
            boolean hasWords = zh.replaceAll("\\{[^}]*}", "").matches(".*[A-Za-z]{4,}.*");
            if (hasWords && zh.equals(english.get(entry.getKey())) && !HAN.matcher(zh).find()) {
                untranslated.add(entry.getKey() + " = " + zh);
            }
        }
        // Proper names and file formats, which Chinese users read in English too.
        untranslated.removeIf(line -> line.startsWith("app.title") || line.startsWith("settings.language.english")
            || line.startsWith("ecdict.tag.deckName") || line.startsWith("anki.column.guid")
            || line.startsWith("import.layout.anki"));
        assertEquals(List.of(), untranslated);
    }

    @Test
    void everyKeyIsUsedAndEveryUsedKeyExists() throws IOException {
        Set<String> keys = bundle(ENGLISH).keySet();
        Set<String> used = new TreeSet<>();
        List<String> computed = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src", "main", "java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Matcher use = KEY_USE.matcher(source);
                while (use.find()) {
                    used.add(use.group(1));
                }
                Matcher dynamic = COMPUTED_KEY.matcher(source);
                while (dynamic.find()) {
                    computed.add(file.getFileName() + ": " + dynamic.group());
                }
            }
        }
        // Messages itself passes its parameter on; everywhere else a key is written out, so it can be found.
        computed.removeIf(use -> use.startsWith("Messages.java: "));
        assertEquals(List.of(), computed, "keys must be literals");

        Set<String> missing = new TreeSet<>(used);
        missing.removeAll(keys);
        Set<String> unused = new TreeSet<>(keys);
        unused.removeAll(used);
        assertEquals(Set.of(), missing, "keys the code uses but the bundles lack");
        assertEquals(Set.of(), unused, "keys no code uses");
    }

    private static void checkPattern(String key, String pattern, Locale locale, List<String> problems) {
        if (pattern.isBlank()) {
            problems.add(key + " (" + locale + "): empty");
            return;
        }
        // Every value is a pattern, so a lone apostrophe would start a quote and swallow text.
        if (pattern.replace("''", "").contains("'")) {
            problems.add(key + " (" + locale + "): a single apostrophe; write it twice");
        }
        try {
            MessageFormat format = new MessageFormat(pattern, locale);
            Object[] samples = new Object[format.getFormatsByArgumentIndex().length + 10];
            for (int i = 0; i < samples.length; i++) {
                samples[i] = 2;
            }
            format.format(samples);
        } catch (IllegalArgumentException e) {
            problems.add(key + " (" + locale + "): " + e.getMessage());
        }
    }

    /** The argument numbers a pattern uses, also inside a choice. */
    private static Set<Integer> arguments(String pattern) {
        Set<Integer> numbers = new TreeSet<>();
        Matcher argument = ARGUMENT.matcher(pattern);
        while (argument.find()) {
            numbers.add(Integer.parseInt(argument.group(1)));
        }
        return numbers;
    }

    /** The bundle's entries in file order; a key that appears twice fails the test. */
    private static Map<String, String> bundle(String resource) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (InputStream in = MessagesBundleTest.class.getResourceAsStream(resource)) {
            assertTrue(in != null, resource + " is missing");
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(text.startsWith("﻿"), resource + " starts with a byte order mark");
            // The values the app reads, with escapes resolved, by the same loader ResourceBundle uses.
            java.util.Properties loaded = new java.util.Properties();
            loaded.load(new java.io.StringReader(text));
            for (String line : text.split("\\R")) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                if (trimmed.endsWith("\\")) {
                    fail(resource + ": continued lines are not used: " + trimmed);
                }
                int separator = trimmed.indexOf('=');
                assertTrue(separator > 0, resource + ": not a key = value line: " + trimmed);
                String key = trimmed.substring(0, separator).strip();
                if (entries.put(key, loaded.getProperty(key)) != null) {
                    fail(resource + ": " + key + " appears twice");
                }
            }
        }
        return entries;
    }
}
