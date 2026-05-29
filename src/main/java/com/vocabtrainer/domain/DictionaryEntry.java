package com.vocabtrainer.domain;

public record DictionaryEntry(
    String english,
    String chinese,
    String partOfSpeech,
    String phonetic,
    String example,
    String source,
    String definition
) {
    public DictionaryEntry(String english, String chinese, String partOfSpeech, String phonetic, String example,
                           String source) {
        this(english, chinese, partOfSpeech, phonetic, example, source, "");
    }
}
