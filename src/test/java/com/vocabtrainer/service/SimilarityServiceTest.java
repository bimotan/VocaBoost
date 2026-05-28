package com.vocabtrainer.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimilarityServiceTest {
    private final SimilarityService service = new SimilarityService();

    @Test
    void exactMatchReturnsOne() {
        assertEquals(1.0, service.calculate("evil", "evil"), 0.0001);
    }

    @Test
    void multipleMeaningsUseBestMatch() {
        assertEquals(1.0, service.calculate("\u51cf\u5c11", "\u51cf\u5f31; \u51cf\u5c11; \u7f13\u548c"), 0.0001);
    }

    @Test
    void normalizesEnglishAndChinese() {
        assertEquals(1.0, service.calculate("Clear Answer!", "clear answer"), 0.0001);
        assertEquals(1.0, service.calculate("\u6e05 \u6670", "\u6e05\u6670"), 0.0001);
    }

    @Test
    void levenshteinKeepsNearMissAboveNoOverlap() {
        double nearMiss = service.calculate("cleer", "clear");
        double noOverlap = service.calculate("abc", "xyz");

        assertTrue(nearMiss > 0.6);
        assertEquals(0.0, noOverlap, 0.0001);
    }

    @Test
    void blankInputReturnsZeroWhenCorrectAnswerExists() {
        assertEquals(0.0, service.calculate("", "complaining"), 0.0001);
    }
}
