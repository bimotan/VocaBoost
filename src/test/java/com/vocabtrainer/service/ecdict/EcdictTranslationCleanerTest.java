package com.vocabtrainer.service.ecdict;

import com.vocabtrainer.service.SimilarityService;
import com.vocabtrainer.service.WordValidationService;
import com.vocabtrainer.service.csv.CsvReader;
import com.vocabtrainer.service.csv.CsvRecord;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EcdictTranslationCleanerTest {
    @Test
    void theRealAbandonRowBecomesAnAnswerKeyWhereTheMainMeaningScoresFull() throws IOException {
        // Review finding D1: the raw translation made "放弃" score 0.5, which the scheduler counts as a lapse.
        String translation = translationOf(EcdictFixtures.ABANDON);
        assertEquals("vt. 放弃, 抛弃, 遗弃, 使屈从, 沉溺, 放纵\\nn. 放任, 无拘束, 狂热", translation);

        EcdictTranslationCleaner.Cleaned cleaned = EcdictTranslationCleaner.clean(translation);

        assertEquals("放弃; 抛弃; 遗弃; 使屈从; 沉溺; 放纵; 放任; 无拘束; 狂热", cleaned.meaning());
        assertEquals("verb; noun", cleaned.partOfSpeech());
        assertEquals("", cleaned.note());
        String answerKey = new WordValidationService().normalizeChinese(cleaned.meaning());
        SimilarityService similarity = new SimilarityService();
        for (String meaning : new String[] {"放弃", "放纵", "放任", "狂热"}) {
            assertEquals(1.0, similarity.calculate(meaning, answerKey), 1e-9, meaning);
        }
    }

    @Test
    void taggedSensesGoToTheNoteAndBracketsKeepTheirCommas() throws IOException {
        EcdictTranslationCleaner.Cleaned cleaned = EcdictTranslationCleaner.clean(translationOf(EcdictFixtures.HOOD));

        assertEquals("罩; 风帽; （布质）面罩; 学位连领帽（表示学位种类）; 覆盖; 用头巾包; 使(马,鹰等)戴头罩; 给…加罩",
            cleaned.meaning());
        assertEquals("noun; verb", cleaned.partOfSpeech());
        assertEquals("[网络] 胡德；兜帽；引擎盖", cleaned.note());
    }

    @Test
    void literalCarriageReturnLineFeedsSplitLinesAndATaggedLineKeepsItsPartOfSpeechOutOfTheAnswer() throws IOException {
        EcdictTranslationCleaner.Cleaned cleaned = EcdictTranslationCleaner.clean(translationOf(EcdictFixtures.A));

        assertEquals("第一个字母 A; 一个; 第一的", cleaned.meaning());
        assertEquals("", cleaned.partOfSpeech());
        assertEquals("art. [计] 累加器, 加法器, 地址, 振幅, 模拟, 区域, 面积, 汇编, 组件, 异步", cleaned.note());
    }

    @Test
    void aWordWithOnlyTaggedSensesKeepsThemAsItsMeaning() {
        EcdictTranslationCleaner.Cleaned cleaned = EcdictTranslationCleaner.clean("[网络] 甲板间；二层舱；双层甲板");

        assertEquals("甲板间; 二层舱; 双层甲板", cleaned.meaning());
        assertEquals("", cleaned.note());
    }

    @Test
    void repeatedSensesAreListedOnceAndMarkerCombinationsMapToOnePartOfSpeech() {
        EcdictTranslationCleaner.Cleaned cleaned = EcdictTranslationCleaner.clean("vt.& vi. 减少, 减弱\\nvi. 减弱\\nadv. 减速地");

        assertEquals("减少; 减弱; 减速地", cleaned.meaning());
        assertEquals("verb; adverb", cleaned.partOfSpeech());
    }

    @Test
    void aWordListMeaningPassesThroughAndRealLineBreaksSeparateSenses() {
        assertEquals("减弱; 减少", EcdictTranslationCleaner.clean("减弱; 减少").meaning());
        assertEquals("清晰的; 易懂的", EcdictTranslationCleaner.clean("清晰的\n易懂的").meaning());
        assertEquals("", EcdictTranslationCleaner.clean("  ").meaning());
        assertEquals("", EcdictTranslationCleaner.clean(null).meaning());
    }

    @Test
    void aVeryLongMeaningKeepsItsFirstSensesAndListsTheRestInTheNote() {
        StringBuilder translation = new StringBuilder("n. ");
        for (int i = 0; i < 60; i++) {
            translation.append(i == 0 ? "" : ", ").append("意义").append(i);
        }

        EcdictTranslationCleaner.Cleaned cleaned = EcdictTranslationCleaner.clean(translation.toString());

        assertTrue(cleaned.meaning().length() <= EcdictTranslationCleaner.MAX_MEANING_LENGTH, cleaned.meaning());
        assertTrue(cleaned.meaning().startsWith("意义0; 意义1; "), cleaned.meaning());
        assertTrue(cleaned.note().startsWith("More meanings: "), cleaned.note());
        assertTrue(cleaned.note().endsWith("; 意义59"), cleaned.note());
    }

    @Test
    void definitionsGetRealLineBreaks() throws IOException {
        assertEquals("n. a tablet placed horizontally on top of the capital of a column as an aid in supporting the architrave\n"
                + "n. a calculator that performs arithmetic functions by manually sliding counters on rods or in grooves",
            EcdictTranslationCleaner.unescapeLines(field(EcdictFixtures.ABACUS, 2)));
    }

    private static String translationOf(String row) throws IOException {
        return field(row, 3);
    }

    private static String field(String row, int index) throws IOException {
        try (CsvReader reader = CsvReader.open(new StringReader(EcdictFixtures.HEADER + "\r\n" + row + "\r\n"))) {
            reader.read();
            CsvRecord record = reader.read();
            return record.get(index);
        }
    }
}
