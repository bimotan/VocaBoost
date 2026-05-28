package com.vocabtrainer.service;

import com.vocabtrainer.domain.ValidatedWord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WordValidationServiceTest {
    private final WordValidationService service = new WordValidationService();

    @Test
    void acceptsReasonableEnglishPhrases() {
        ValidatedWord word = service.validate("  ill-advised plan  ", "不明智的，欠考虑的");

        assertEquals("ill-advised plan", word.english());
        assertEquals("不明智的; 欠考虑的", word.chinese());
    }

    @Test
    void rejectsInvalidEnglishCharacters() {
        assertThrows(IllegalArgumentException.class, () -> service.validate("bad@word", "坏词"));
    }

    @Test
    void rejectsEmptyFields() {
        assertThrows(IllegalArgumentException.class, () -> service.validate("", "释义"));
        assertThrows(IllegalArgumentException.class, () -> service.validate("lucid", ""));
    }
}
