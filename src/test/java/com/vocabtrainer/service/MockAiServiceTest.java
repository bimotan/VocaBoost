package com.vocabtrainer.service;

import com.vocabtrainer.domain.WordCard;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockAiServiceTest {
    @Test
    void mockAiIsOfflineAndReturnsUsefulText() {
        WordCard word = WordCard.createNew(1, "lucid", "清晰的");
        String text = new MockAiService().explain(word);

        assertFalse(new MockAiService().isAvailable());
        assertTrue(text.contains("lucid"));
        assertTrue(text.contains("清晰"));
        assertTrue(text.contains("Example"));
    }
}
