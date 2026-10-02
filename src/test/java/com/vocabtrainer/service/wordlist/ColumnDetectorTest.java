package com.vocabtrainer.service.wordlist;

import com.vocabtrainer.service.csv.WordColumn;
import com.vocabtrainer.service.csv.WordColumns;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColumnDetectorTest {
    @Test
    void aGreCsvWithoutAHeaderKeepsItsUsualOrder() {
        WordColumns columns = detect(List.of(
            List.of("abate", "减弱; 减少", "verb", "The storm began to abate.", "gre"),
            List.of("lucid", "清晰的", "adjective", "", "gre")));

        assertEquals("english, chinese, pos, example, tags", columns.describe());
    }

    @Test
    void eachFieldGoesToTheColumnThatLooksLikeIt() {
        WordColumns columns = detect(List.of(
            List.of("The storm began to abate.", "v.", "/əˈbeɪt/", "减弱", "abate"),
            List.of("His talk was lucid.", "adj.", "/ˈluːsɪd/", "清晰的", "lucid")));

        assertEquals(4, columns.index(WordColumn.ENGLISH));
        assertEquals(3, columns.index(WordColumn.CHINESE));
        assertEquals(2, columns.index(WordColumn.PHONETIC));
        assertEquals(1, columns.index(WordColumn.POS));
        assertEquals(0, columns.index(WordColumn.EXAMPLE));
    }

    @Test
    void aListOfPhrasesHasItsPhrasesAsTheWords() {
        WordColumns columns = detect(List.of(
            List.of("放弃", "give up on something you love"),
            List.of("坚持", "hold on to your dream for years")));

        assertEquals(1, columns.index(WordColumn.ENGLISH));
        assertEquals(0, columns.index(WordColumn.CHINESE));
        assertFalse(columns.has(WordColumn.EXAMPLE));
    }

    @Test
    void oneColumnIsAListOfWords() {
        WordColumns columns = detect(List.of(List.of("abate"), List.of("lucid"), List.of("rock 'n' roll")));

        assertEquals("english", columns.describe());
    }

    @Test
    void skippedColumnsAndKnownFieldsAreLeftAlone() {
        WordColumns known = WordColumns.none().with(WordColumn.TAGS, 5);
        WordColumns columns = ColumnDetector.detect(known, List.of(
                List.of("guid1", "Basic", "GRE", "abate", "减弱", "gre verb"),
                List.of("guid2", "Basic", "GRE", "lucid", "清晰的", "gre")),
            6, Set.of(0, 1, 2), true);

        assertEquals(3, columns.index(WordColumn.ENGLISH));
        assertEquals(4, columns.index(WordColumn.CHINESE));
        assertEquals(5, columns.index(WordColumn.TAGS));
        assertEquals("english, chinese, tags", columns.describe());
    }

    @Test
    void partsOfSpeechAndPhoneticsAreRecognisedAlone() {
        assertTrue(ColumnDetector.isPartOfSpeech("adj."));
        assertTrue(ColumnDetector.isPartOfSpeech("n./v."));
        assertTrue(ColumnDetector.isPartOfSpeech("Transitive verb"));
        assertTrue(ColumnDetector.isPartOfSpeech("形容词"));
        assertFalse(ColumnDetector.isPartOfSpeech("adj. 清晰的"));
        assertFalse(ColumnDetector.isPartOfSpeech("abate"));
        assertTrue(ColumnDetector.isPhonetic("/əˈbeɪt/"));
        assertTrue(ColumnDetector.isPhonetic("[ə'beit]"));
        assertFalse(ColumnDetector.isPhonetic("[网络] 胡德"));
        assertFalse(ColumnDetector.isPhonetic("abate"));
        assertTrue(ColumnDetector.isPhonetic("[ri:d]"));
        assertTrue(ColumnDetector.isPhonetic("/a'beit/"));
        assertFalse(ColumnDetector.isPhonetic("[formal]"), "a usage label");
        assertFalse(ColumnDetector.isPhonetic("[pl.]"));
        assertFalse(ColumnDetector.isPhonetic("/and/"));
    }

    private static WordColumns detect(List<List<String>> rows) {
        int width = rows.stream().mapToInt(List::size).max().orElse(0);
        return ColumnDetector.detect(WordColumns.none(), rows, width, Set.of(), true);
    }
}
