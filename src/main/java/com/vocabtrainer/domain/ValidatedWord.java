package com.vocabtrainer.domain;

public record ValidatedWord(
    String english,
    String chinese,
    String phonetic,
    String partOfSpeech,
    String exampleSentence,
    String note,
    String tags
) {
}
