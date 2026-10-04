package com.vocabtrainer.service.cloze;

import com.vocabtrainer.service.csv.CsvReader;
import com.vocabtrainer.service.csv.CsvRecord;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The bundled starter words' example sentences make Cloze review useful (review finding G1): every
 * one uses its word, also inflected, in a sentence long enough to give context, so each starter word
 * can be asked as a cloze without a dictionary's irregular forms.
 */
class StarterExamplesTest {
    private static final int MIN_WORDS = 12;
    private static final int MAX_WORDS = 25;

    private final ClozeMaker rulesOnly = new ClozeMaker(WordForms.NONE);

    @Test
    void everyStarterExampleMakesAClozeOfItsWord() throws IOException {
        Map<String, String> examples = starterExamples();
        List<String> withoutCloze = new ArrayList<>();
        examples.forEach((english, example) -> {
            Optional<Cloze> cloze = rulesOnly.make(example, english);
            if (cloze.isEmpty() || cloze.get().masked().equals(example)) {
                withoutCloze.add(english + ": " + example);
            }
        });

        assertEquals(215, examples.size());
        assertEquals(List.of(), withoutCloze, "starter examples that make no cloze of their word");
    }

    @Test
    void everyStarterExampleIsASentenceWithContext() throws IOException {
        List<String> wrongLength = new ArrayList<>();
        starterExamples().forEach((english, example) -> {
            int words = example.strip().split("\\s+").length;
            if (words < MIN_WORDS || words > MAX_WORDS || !example.endsWith(".")) {
                wrongLength.add(english + " (" + words + " words): " + example);
            }
        });

        assertEquals(List.of(), wrongLength, "starter examples outside " + MIN_WORDS + " to " + MAX_WORDS + " words");
    }

    /** The starter words' examples by English word, read as the starter import reads the file. */
    private static Map<String, String> starterExamples() throws IOException {
        Map<String, String> examples = new LinkedHashMap<>();
        try (InputStream input = StarterExamplesTest.class.getResourceAsStream("/data/gre_starter_sample.csv");
             CsvReader csv = CsvReader.open(input, StandardCharsets.UTF_8)) {
            CsvRecord header = csv.read();
            int english = header.fields().indexOf("english");
            int example = header.fields().indexOf("example");
            for (CsvRecord row = csv.read(); row != null; row = csv.read()) {
                examples.put(row.fields().get(english), row.fields().get(example));
            }
        }
        return examples;
    }
}
