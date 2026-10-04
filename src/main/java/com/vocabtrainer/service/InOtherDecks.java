package com.vocabtrainer.service;

import com.vocabtrainer.domain.WordCard;

/**
 * What a word list import or an ECDICT tag deck does with a word that another active deck already
 * has. Either way a word that is added gets its own schedule in its deck (see docs/ARCHITECTURE.md,
 * Word List and Decks).
 */
public enum InOtherDecks {
    /** Add it with what the file or ECDICT has. */
    KEEP_IMPORTED,
    /**
     * Add it with the other deck's meaning, part of speech, example and phonetic (the oldest deck
     * that has it); a value that deck lacks keeps the imported one. Note and tags stay the import's.
     */
    COPY_DETAILS,
    /** Do not add it. */
    SKIP;

    /**
     * {@code word} with the details of {@code other} copied into it as {@link #COPY_DETAILS} says;
     * the word is changed and returned.
     */
    public static WordCard copyDetails(WordCard other, WordCard word) {
        word.setChinese(orElse(other.getChinese(), word.getChinese()));
        word.setPartOfSpeech(orElse(other.getPartOfSpeech(), word.getPartOfSpeech()));
        word.setExampleSentence(orElse(other.getExampleSentence(), word.getExampleSentence()));
        word.setPhonetic(orElse(other.getPhonetic(), word.getPhonetic()));
        return word;
    }

    private static String orElse(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
