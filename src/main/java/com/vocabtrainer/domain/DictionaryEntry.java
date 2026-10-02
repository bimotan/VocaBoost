package com.vocabtrainer.domain;

/**
 * A dictionary result that can fill the add form.
 *
 * @param definition an English definition
 * @param note       what the dictionary has beyond the meaning, such as ECDICT's domain-tagged senses
 *                   ("[网络] ..."); empty when there is nothing
 */
public record DictionaryEntry(
    String english,
    String chinese,
    String partOfSpeech,
    String phonetic,
    String example,
    String source,
    String definition,
    String note
) {
    public DictionaryEntry(String english, String chinese, String partOfSpeech, String phonetic, String example,
                           String source) {
        this(english, chinese, partOfSpeech, phonetic, example, source, "");
    }

    public DictionaryEntry(String english, String chinese, String partOfSpeech, String phonetic, String example,
                           String source, String definition) {
        this(english, chinese, partOfSpeech, phonetic, example, source, definition, "");
    }
}
