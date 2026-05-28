package com.vocabtrainer.domain;

public record DictionaryEntry(
    String english,
    String chinese,
    String partOfSpeech,
    String phonetic,
    String example,
    String source
) {
}
