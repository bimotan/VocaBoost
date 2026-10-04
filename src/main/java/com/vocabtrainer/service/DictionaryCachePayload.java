package com.vocabtrainer.service;

import com.vocabtrainer.domain.DictionaryEntry;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * How {@code dictionary_cache.payload} stores a lookup's entries: one line per entry, its fields
 * Base64-encoded and separated by tabs. The fields are the word, Chinese meaning, part of speech,
 * phonetic, example, source, English definition and note; an entry with synonyms, antonyms or a
 * recording has three more: the synonyms and the antonyms (one per line before encoding) and the
 * recording's URL. Entries without them keep the eight fields earlier versions write and read; an
 * earlier version skips a line with eleven fields, so it looks such a word up again.
 */
final class DictionaryCachePayload {
    private static final Logger LOGGER = Logger.getLogger(DictionaryCachePayload.class.getName());
    private static final String PLACEHOLDER_PREFIX = "请填写中文释义（English definition: ";

    private DictionaryCachePayload() {
    }

    static String serialize(List<DictionaryEntry> entries) {
        List<String> rows = new ArrayList<>();
        for (DictionaryEntry entry : entries) {
            List<String> fields = new ArrayList<>(List.of(
                encode(entry.english()),
                encode(entry.chinese()),
                encode(entry.partOfSpeech()),
                encode(entry.phonetic()),
                encode(entry.example()),
                encode(entry.source()),
                encode(entry.definition()),
                encode(entry.note())));
            if (entry.hasExtras()) {
                fields.add(encode(String.join("\n", entry.synonyms())));
                fields.add(encode(String.join("\n", entry.antonyms())));
                fields.add(encode(entry.audioUrl()));
            }
            rows.add(String.join("\t", fields));
        }
        return String.join("\n", rows);
    }

    /** The entries of a payload; none when it cannot be read, so the row is replaced. */
    static List<DictionaryEntry> deserialize(String payload) {
        try {
            return deserializeRows(payload == null ? "" : payload);
        } catch (IllegalArgumentException e) {
            LOGGER.log(Level.WARNING, "Ignoring a dictionary cache entry that cannot be read", e);
            return List.of();
        }
    }

    private static List<DictionaryEntry> deserializeRows(String payload) {
        List<DictionaryEntry> entries = new ArrayList<>();
        for (String row : payload.split("\\R")) {
            if (row.isBlank()) {
                continue;
            }
            String[] fields = row.split("\\t", -1);
            if (fields.length < 6 || (fields.length > 8 && fields.length != 11)) {
                continue;
            }
            String chinese = decode(fields[1]);
            String definition = fields.length >= 7 ? decode(fields[6]) : "";
            if (looksLikeOnlineDefinitionPlaceholder(chinese)) {
                definition = extractDefinition(chinese);
                chinese = "";
            }
            boolean extras = fields.length == 11;
            entries.add(new DictionaryEntry(
                decode(fields[0]),
                chinese,
                decode(fields[2]),
                decode(fields[3]),
                decode(fields[4]),
                decode(fields[5]),
                definition,
                fields.length >= 8 ? decode(fields[7]) : "",
                extras ? lines(decode(fields[8])) : List.of(),
                extras ? lines(decode(fields[9])) : List.of(),
                extras ? decode(fields[10]) : ""
            ));
        }
        return entries;
    }

    private static List<String> lines(String text) {
        return Arrays.stream(text.split("\n")).map(String::strip).filter(line -> !line.isEmpty()).toList();
    }

    private static String encode(String value) {
        String safe = value == null ? "" : value;
        return Base64.getEncoder().encodeToString(safe.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    /** What some earlier versions saved as the Chinese meaning of an English-only entry. */
    private static boolean looksLikeOnlineDefinitionPlaceholder(String value) {
        return value != null && value.startsWith(PLACEHOLDER_PREFIX);
    }

    private static String extractDefinition(String value) {
        String definition = value.substring(PLACEHOLDER_PREFIX.length());
        if (definition.endsWith("）")) {
            definition = definition.substring(0, definition.length() - 1);
        }
        return definition.trim();
    }
}
