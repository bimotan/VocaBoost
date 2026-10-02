package com.vocabtrainer.ui;

import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.service.cloze.SentenceSpan;

import java.util.List;

/**
 * What a word's details card shows besides the word and its meaning: phonetic, part of speech, the
 * example sentence with the word marked, note and tags. Empty strings for what the word does not have.
 *
 * @param example the example split into the word (also inflected) and the text around it, see
 *                {@link com.vocabtrainer.service.cloze.ClozeMaker#highlight}; empty without one
 */
public record WordDetails(String phonetic, String partOfSpeech, List<SentenceSpan> example, String note,
                          String tags) {
    public WordDetails {
        phonetic = clean(phonetic);
        partOfSpeech = clean(partOfSpeech);
        example = example == null ? List.of() : List.copyOf(example);
        note = clean(note);
        tags = clean(tags);
    }

    /** The details of {@code word}, with its example already split by {@code example}. */
    public static WordDetails of(WordCard word, List<SentenceSpan> example) {
        return new WordDetails(word.getPhonetic(), word.getPartOfSpeech(), example, word.getNote(), word.getTags());
    }

    /** The example sentence as plain text. */
    public String exampleText() {
        StringBuilder text = new StringBuilder();
        example.forEach(span -> text.append(span.text()));
        return text.toString();
    }

    /** Whether there is nothing to show. */
    public boolean isEmpty() {
        return phonetic.isEmpty() && partOfSpeech.isEmpty() && example.isEmpty() && note.isEmpty() && tags.isEmpty();
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }
}
