package com.vocabtrainer.domain;

import java.util.List;

/**
 * A dictionary result that can fill the add form.
 *
 * @param definition an English definition
 * @param note       what the dictionary has beyond the meaning, such as ECDICT's domain-tagged senses
 *                   ("[网络] ..."); empty when there is nothing
 * @param synonyms   synonyms the dictionary lists for this sense (dictionaryapi.dev); empty when none
 * @param antonyms   antonyms the dictionary lists for this sense; empty when none
 * @param audioUrl   a recording of the word's pronunciation (dictionaryapi.dev); empty when none
 */
public record DictionaryEntry(
    String english,
    String chinese,
    String partOfSpeech,
    String phonetic,
    String example,
    String source,
    String definition,
    String note,
    List<String> synonyms,
    List<String> antonyms,
    String audioUrl
) {
    public DictionaryEntry {
        synonyms = synonyms == null ? List.of() : List.copyOf(synonyms);
        antonyms = antonyms == null ? List.of() : List.copyOf(antonyms);
        audioUrl = audioUrl == null ? "" : audioUrl.strip();
    }

    public DictionaryEntry(String english, String chinese, String partOfSpeech, String phonetic, String example,
                           String source) {
        this(english, chinese, partOfSpeech, phonetic, example, source, "");
    }

    public DictionaryEntry(String english, String chinese, String partOfSpeech, String phonetic, String example,
                           String source, String definition) {
        this(english, chinese, partOfSpeech, phonetic, example, source, definition, "");
    }

    public DictionaryEntry(String english, String chinese, String partOfSpeech, String phonetic, String example,
                           String source, String definition, String note) {
        this(english, chinese, partOfSpeech, phonetic, example, source, definition, note, List.of(), List.of(), "");
    }

    /** Whether the entry has synonyms, antonyms or a recording, which only some dictionaries give. */
    public boolean hasExtras() {
        return !synonyms.isEmpty() || !antonyms.isEmpty() || !audioUrl.isEmpty();
    }
}
