package com.vocabtrainer.service;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bundled starter words make unambiguous Chinese-to-English prompts (review finding E12): every
 * word has a meaning no other starter word has, so its prompt points at it, and the words that do
 * share a meaning accept each other as synonyms.
 */
class StarterGlossTest {
    private static final Pattern ROW = Pattern.compile("^([^,]+),\"([^\"]*)\",");

    private final SimilarityService similarity = new SimilarityService();

    @Test
    void everyStarterWordHasAMeaningOfItsOwn() throws IOException {
        Map<String, String> glosses = starterGlosses();
        Map<String, Set<String>> wordsByMeaning = new HashMap<>();
        glosses.forEach((english, gloss) -> similarity.meaningKeys(gloss)
            .forEach(meaning -> wordsByMeaning.computeIfAbsent(meaning, key -> new HashSet<>()).add(english)));

        List<String> ambiguous = new ArrayList<>();
        glosses.forEach((english, gloss) -> {
            boolean ownMeaning = similarity.meaningKeys(gloss).stream()
                .anyMatch(meaning -> wordsByMeaning.get(meaning).size() == 1);
            if (!ownMeaning) {
                ambiguous.add(english + " (" + gloss + ")");
            }
        });

        assertEquals(215, glosses.size());
        assertEquals(List.of(), ambiguous, "starter words whose every meaning another word shares");
    }

    @Test
    void starterWordsThatShareAMeaningAreSynonymsOfEachOther() throws IOException {
        Map<String, String> glosses = starterGlosses();
        AnswerGrader grader = new AnswerGrader(similarity);

        assertTrue(grader.sharesMeaning(glosses.get("capricious"), glosses.get("mercurial")));
        assertTrue(grader.sharesMeaning(glosses.get("mitigate"), glosses.get("alleviate")));
        assertTrue(grader.sharesMeaning(glosses.get("deleterious"), glosses.get("pernicious")));
        assertFalse(grader.sharesMeaning(glosses.get("lucid"), glosses.get("abate")));
    }

    private static Map<String, String> starterGlosses() throws IOException {
        Map<String, String> glosses = new LinkedHashMap<>();
        try (InputStream input = StarterGlossTest.class.getResourceAsStream("/data/gre_starter_sample.csv");
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher row = ROW.matcher(line);
                if (row.find()) {
                    glosses.put(row.group(1), row.group(2));
                }
            }
        }
        return glosses;
    }
}
