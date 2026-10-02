package com.vocabtrainer.ui;

import com.vocabtrainer.domain.ValidatedWord;
import com.vocabtrainer.domain.WordCard;

import java.util.Locale;

/** Copies validated form input onto words; shared by the add form and the edit dialog. */
public final class WordFields {
    private WordFields() {
    }

    /** Copies everything except English and Chinese, which the callers set themselves. */
    public static void applyValidatedFields(WordCard word, ValidatedWord validated) {
        word.setPhonetic(validated.phonetic());
        word.setPartOfSpeech(validated.partOfSpeech());
        word.setExampleSentence(validated.exampleSentence());
        word.setNote(validated.note());
        word.setTags(validated.tags());
    }

    /** {@code tags} plus {@code tag}, separated by "; ", unless the tags already contain it. */
    public static String appendTag(String tags, String tag) {
        String clean = tags == null ? "" : tags.trim();
        if (tag == null || tag.isBlank()) {
            return clean;
        }
        if (clean.toLowerCase(Locale.ROOT).contains(tag.toLowerCase(Locale.ROOT))) {
            return clean;
        }
        return clean.isBlank() ? tag : clean + "; " + tag;
    }
}
