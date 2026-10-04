package com.vocabtrainer.service;

import com.vocabtrainer.domain.DictionaryEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What an online dictionary (dictionaryapi.dev) knows about a word besides its meaning: synonyms,
 * antonyms and a recording of its pronunciation. Empty lists and an empty URL for what is unknown.
 */
public record WordExtras(List<String> synonyms, List<String> antonyms, String audioUrl) {
    /** At most this many synonyms and antonyms are kept, the dictionary's first ones. */
    static final int MAX_WORDS = 10;
    public static final WordExtras NONE = new WordExtras(List.of(), List.of(), "");

    public WordExtras {
        synonyms = synonyms == null ? List.of() : List.copyOf(synonyms);
        antonyms = antonyms == null ? List.of() : List.copyOf(antonyms);
        audioUrl = audioUrl == null ? "" : audioUrl.strip();
    }

    /**
     * The extras of the entries for {@code english} itself (ignoring case), never those of a base
     * form an inflection was found under: their synonyms and antonyms in the dictionary's order,
     * each once, and the first recording. The word itself is never its own synonym.
     */
    public static WordExtras of(String english, List<DictionaryEntry> entries) {
        String asked = english == null ? "" : english.strip().toLowerCase(Locale.ROOT);
        Map<String, String> synonyms = new LinkedHashMap<>();
        Map<String, String> antonyms = new LinkedHashMap<>();
        String audio = "";
        for (DictionaryEntry entry : entries) {
            if (entry.english() == null || !entry.english().strip().toLowerCase(Locale.ROOT).equals(asked)) {
                continue;
            }
            collect(entry.synonyms(), asked, synonyms);
            collect(entry.antonyms(), asked, antonyms);
            if (audio.isEmpty()) {
                audio = entry.audioUrl();
            }
        }
        return new WordExtras(new ArrayList<>(synonyms.values()), new ArrayList<>(antonyms.values()), audio);
    }

    private static void collect(List<String> words, String asked, Map<String, String> into) {
        for (String word : words) {
            String clean = word == null ? "" : word.strip();
            String key = clean.toLowerCase(Locale.ROOT);
            if (!clean.isEmpty() && !key.equals(asked) && into.size() < MAX_WORDS) {
                into.putIfAbsent(key, clean);
            }
        }
    }

    public boolean hasAudio() {
        return !audioUrl.isEmpty();
    }

    /** Whether nothing is known. */
    public boolean isEmpty() {
        return synonyms.isEmpty() && antonyms.isEmpty() && audioUrl.isEmpty();
    }
}
