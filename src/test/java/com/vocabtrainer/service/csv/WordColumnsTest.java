package com.vocabtrainer.service.csv;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WordColumnsTest {
    @Test
    void headerNamesAreMatchedIgnoringCaseSpacesAndUnderscores() {
        WordColumns columns = header("Word", "Meaning", "Part of Speech", "example_sentence", "Notes", "IPA", "Tags");

        assertEquals(0, columns.index(WordColumn.ENGLISH));
        assertEquals(1, columns.index(WordColumn.CHINESE));
        assertEquals(2, columns.index(WordColumn.POS));
        assertEquals(3, columns.index(WordColumn.EXAMPLE));
        assertEquals(4, columns.index(WordColumn.NOTE));
        assertEquals(5, columns.index(WordColumn.PHONETIC));
        assertEquals(6, columns.index(WordColumn.TAGS));
        assertTrue(columns.fromHeader());
    }

    @Test
    void chineseHeaderNames() {
        WordColumns columns = header("单词", "音标", "词性", "释义", "例句", "备注", "标签");

        assertEquals("english, phonetic, pos, chinese, example, note, tags", columns.describe());
    }

    @Test
    void theExportHeaderNamesEveryColumnInExportOrder() {
        String[] names = WordColumns.EXPORT_ORDER.stream().map(WordColumn::headerName).toArray(String[]::new);

        assertEquals(List.of("english", "chinese", "phonetic", "pos", "example", "note", "tags"), Arrays.asList(names));
        WordColumns columns = header(names);
        for (int i = 0; i < WordColumns.EXPORT_ORDER.size(); i++) {
            assertEquals(i, columns.index(WordColumns.EXPORT_ORDER.get(i)));
        }
    }

    @Test
    void columnsMayComeInAnyOrderAndUnknownOnesAreIgnored() {
        WordColumns columns = header("source", "Chinese", "level", "English");

        assertEquals(3, columns.index(WordColumn.ENGLISH));
        assertEquals(1, columns.index(WordColumn.CHINESE));
        assertFalse(columns.has(WordColumn.POS));
        assertEquals("", columns.get(new CsvRecord(List.of("x", "清晰的", "1", "lucid"), 2), WordColumn.POS));
    }

    @Test
    void theEarlierAliasWinsWhenAHeaderHasTwoNamesForOneField() {
        // ECDICT: "definition" is the English definition, "translation" the Chinese.
        WordColumns columns = header("word", "phonetic", "definition", "translation", "pos", "collins", "tag");

        assertEquals(3, columns.index(WordColumn.CHINESE));
    }

    @Test
    void aByteOrderMarkInTheFirstHeaderCellIsIgnored() {
        assertEquals(0, header("\uFEFFenglish", "chinese").index(WordColumn.ENGLISH));
    }

    @Test
    void dataRowsAreNotHeaders() {
        assertTrue(WordColumns.fromHeader(record("lucid", "清晰的", "adjective")).isEmpty());
        // Two names of the same field are a word and its meaning, not a header.
        assertTrue(WordColumns.fromHeader(record("note", "笔记")).isEmpty());
        assertTrue(WordColumns.fromHeader(record("word", "单词", "noun")).isEmpty());
        // A one-column file is a header only when its cell is a column name.
        assertTrue(WordColumns.fromHeader(record("abate")).isEmpty());
        assertEquals(0, WordColumns.fromHeader(record("english")).orElseThrow().index(WordColumn.ENGLISH));
    }

    @Test
    void aFirstRowThatStartsWithTheEnglishColumnNameIsAHeaderWithUnknownNames() {
        assertTrue(WordColumns.fromHeader(record("English", "Meaning (中文)")).isEmpty());
        assertTrue(WordColumns.startsWithEnglishColumnName(record("English", "Meaning (中文)")));
        assertTrue(WordColumns.startsWithEnglishColumnName(record("word", "汉语解释", "")));
        // Data rows: a word with its meaning, including the word "word" itself.
        assertFalse(WordColumns.startsWithEnglishColumnName(record("word", "单词", "noun")));
        assertFalse(WordColumns.startsWithEnglishColumnName(record("note", "笔记")));
        assertFalse(WordColumns.startsWithEnglishColumnName(record("lucid", "清晰的")));
    }

    @Test
    void commonChineseHeadersForTheWordAndItsMeaning() {
        WordColumns columns = header("英文单词", "中文翻译");

        assertEquals(0, columns.index(WordColumn.ENGLISH));
        assertEquals(1, columns.index(WordColumn.CHINESE));
        assertEquals(1, header("英语单词", "中文含义").index(WordColumn.CHINESE));
    }

    @Test
    void positionalColumnsMaySkipAColumn() {
        WordColumns columns = WordColumns.positional(WordColumn.ENGLISH, WordColumn.PHONETIC, null, WordColumn.CHINESE);
        CsvRecord row = record("lucid", "ˈluːsɪd", "clear", "清晰的");

        assertEquals("清晰的", columns.get(row, WordColumn.CHINESE));
        assertFalse(columns.has(WordColumn.EXAMPLE));
        assertFalse(columns.fromHeader());
    }

    @Test
    void valuesLoseTheApostropheThatExportAddedAgainstFormulas() {
        WordColumns columns = WordColumns.positional(WordColumn.ENGLISH, WordColumn.NOTE);

        assertEquals("-ness", columns.get(record("ness", "'-ness"), WordColumn.NOTE));
        assertEquals("'plain", columns.get(record("plain", "'plain"), WordColumn.NOTE));
    }

    private static WordColumns header(String... cells) {
        return WordColumns.fromHeader(record(cells)).orElseThrow(() -> new AssertionError("Not a header: " + List.of(cells)));
    }

    private static CsvRecord record(String... cells) {
        return new CsvRecord(List.of(cells), 1);
    }
}
