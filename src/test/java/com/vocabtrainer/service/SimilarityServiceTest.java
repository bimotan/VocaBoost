package com.vocabtrainer.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SimilarityServiceTest {
    private final SimilarityService service = new SimilarityService();

    @Test
    void exactMatchReturnsOne() {
        assertEquals(1.0, service.calculate("邪恶的", "邪恶的"), 0.0001);
    }

    @Test
    void partialChineseMeaningUsesJaccard() {
        double similarity = service.calculate("邪恶", "邪恶的恶毒的");
        assertEquals(0.5, similarity, 0.0001);
    }

    @Test
    void blankInputReturnsZeroWhenCorrectAnswerExists() {
        assertEquals(0.0, service.calculate("", "抱怨的"), 0.0001);
    }

    @Test
    void noOverlapReturnsZero() {
        assertEquals(0.0, service.calculate("开心", "邪恶"), 0.0001);
    }
}