package com.vocabtrainer.service;

import com.vocabtrainer.domain.DictionaryEntry;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The dictionary_cache payload keeps synonyms, antonyms and recordings without breaking older rows (G2). */
class DictionaryCachePayloadTest {
    private static final DictionaryEntry PLAIN = new DictionaryEntry("lucid", "", "adjective", "/ˈluːsɪd/",
        "a lucid explanation", "dictionaryapi.dev", "Clear; easily understood.", "");
    private static final DictionaryEntry WITH_EXTRAS = new DictionaryEntry("abate", "", "verb", "/əˈbeɪt/", "",
        "dictionaryapi.dev", "To lessen.", "", List.of("subside", "wane"), List.of("intensify"),
        "https://api.dictionaryapi.dev/media/pronunciations/en/abate-us.mp3");

    @Test
    void entriesWithAndWithoutExtrasComeBackAsTheyWere() {
        String payload = DictionaryCachePayload.serialize(List.of(PLAIN, WITH_EXTRAS));

        assertEquals(List.of(PLAIN, WITH_EXTRAS), DictionaryCachePayload.deserialize(payload));
        String[] rows = payload.split("\n");
        assertEquals(8, rows[0].split("\t", -1).length, "an entry without extras is written as earlier versions read it");
        assertEquals(11, rows[1].split("\t", -1).length);
    }

    @Test
    void rowsOfEarlierVersionsAreReadAndUnknownShapesSkipped() {
        String sixFields = String.join("\t", encode("lucid"), encode(""), encode("adjective"), encode(""), encode(""),
            encode("dictionaryapi.dev"));
        String nineFields = sixFields + "\t" + encode("") + "\t" + encode("") + "\t" + encode("");

        List<DictionaryEntry> entries = DictionaryCachePayload.deserialize(sixFields + "\n" + nineFields);

        assertEquals(1, entries.size());
        assertEquals("lucid", entries.get(0).english());
        assertTrue(entries.get(0).synonyms().isEmpty());
        assertEquals(List.of(), DictionaryCachePayload.deserialize("not base64 at all\tx\ty\tz\tw\tv"));
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
