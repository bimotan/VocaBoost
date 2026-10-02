package com.vocabtrainer.service;

import com.vocabtrainer.domain.ReviewRating;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The meaning comparator of English-to-Chinese questions and the spelling comparator of
 * Chinese-to-English ones (review findings A3 and D1). The values in the comments are what the
 * single character-overlap comparator of earlier versions gave.
 */
class SimilarityServiceTest {
    /** lucid in the starter deck. */
    private static final String LUCID = "清晰的; 明白易懂的";
    /** abate in the starter deck. */
    private static final String ABATE = "减弱; 减少";
    /** An ECDICT translation as its CSV stores it, with a literal backslash-n between parts of speech. */
    private static final String ECDICT_ABANDON = "vt. 放弃, 抛弃, 遗弃, 使屈从, 沉溺, 放纵\\nn. 放任, 无拘束, 狂热";

    private final SimilarityService service = new SimilarityService();

    @ParameterizedTest(name = "\"{0}\" matches \"{1}\"")
    @CsvSource(delimiter = '|', textBlock = """
        evil         | evil
        清晰         | 清晰的; 明白易懂的
        清晰的       | 清晰的; 明白易懂的
        明白易懂的   | 清晰的; 明白易懂的
        明白易懂     | 清晰的; 明白易懂的
        减少         | 减弱; 减少
        减弱；减少   | 减弱; 减少
        减弱 减少    | 减弱; 减少
        减弱，完全错误 | 减弱; 减少
        Clear Answer! | clear answer
        清 晰        | 清晰
        清晰的。     | 清晰的
        迅速         | 迅速地
        放弃         | vt. 放弃; 抛弃\\nn. 放任
        放任         | vt. 放弃; 抛弃\\nn. 放任
        减轻         | vt. 减轻, 减少, 降低\\nvi. 减弱, 减退
        降低         | vt. 减轻, 减少, 降低\\nvi. 减弱, 减退
        减弱         | vt. 减轻, 减少, 降低\\nvi. 减弱, 减退
        减弱         | vt. & vi. 减弱
        清晰         | ADJ. 清晰的
        adj. 清晰的  | 清晰的
        放弃         | [网络] 放弃; 遗弃
        急救         | 【医】急救
        减弱         | (使)减弱
        使减弱       | （使）减弱
        使戴头罩     | 使(马, 鹰等)戴头罩
        使马鹰等戴头罩 | 使(马, 鹰等)戴头罩
        """)
    void aTypedMeaningThatEqualsOneOfTheGlossScoresOne(String typed, String gloss) {
        assertEquals(1.0, service.calculate(typed, gloss), 1e-9);
    }

    @Test
    void theNumbersOfFindingA3() {
        assertEquals(1.0, service.calculate("清晰", LUCID), 1e-9, "was 0.667, capped at Hard");
        assertEquals(1.0, service.calculate("明白易懂的", LUCID), 1e-9);
        assertEquals(1.0, service.calculate("减弱；减少", ABATE), 1e-9, "was 0.575: both meanings scored below one");
        assertEquals(1.0, service.calculate("减少", ABATE), 1e-9);
        // A synonym the comparator cannot know stays below Good; the user can override the check.
        double synonym = service.calculate("清楚", LUCID);
        assertTrue(synonym < ReviewRating.MIN_SIMILARITY_GOOD, "清楚 for lucid: " + synonym);
        assertEquals(ReviewRating.AGAIN, ReviewRating.maxForSimilarity(synonym));
    }

    @Test
    void anEcdictTranslationIsSplitIntoItsMeaningsWithoutPartsOfSpeech() {
        // Finding D1: 放弃 scored 0.5 (a lapse) and 放任 0.408, glued to "vt" and "放纵\nn".
        for (String meaning : List.of("放弃", "抛弃", "放纵", "放任", "狂热")) {
            assertEquals(1.0, service.calculate(meaning, ECDICT_ABANDON), 1e-9, meaning);
        }
        assertEquals(List.of("放弃", "抛弃", "遗弃", "使屈从", "沉溺", "放纵", "放任", "无拘束", "狂热"),
            service.splitMeanings(ECDICT_ABANDON));
        assertEquals(List.of("放弃", "急救", "减弱", "清楚的"),
            service.splitMeanings("[网络] 放弃; 【医】急救; vt. & vi. 减弱 adj. 清楚的"));
        assertEquals(List.of("使(马, 鹰等)戴头罩"), service.splitMeanings("使(马, 鹰等)戴头罩"));
    }

    @Test
    void aTrailingParticleIsDroppedOnlyWhenTwoCharactersRemain() {
        assertEquals(1.0, service.calculate("清晰", "清晰的"), 1e-9);
        assertTrue(service.calculate("获", "获得") < 1.0, "得 is part of 获得");
        assertTrue(service.calculate("基", "基地") < 1.0, "地 is part of 基地");
    }

    @Test
    void aMeaningThatIsNotListedScoresByCharacterBigramsAndEditDistance() {
        assertEquals(0.25, service.calculate("清楚", LUCID), 1e-9);
        assertEquals(0.5, service.calculate("易懂", LUCID), 1e-9);
        assertEquals(0.0, service.calculate("abc", "xyz"), 1e-9);
        assertTrue(service.calculate("cleer", "clear") > 0.6);
    }

    @Test
    void blankAnswers() {
        assertEquals(0.0, service.calculate("", "complaining"), 1e-9);
        assertEquals(0.0, service.calculate(null, ABATE), 1e-9);
        assertEquals(1.0, service.calculate(" ", ""), 1e-9);
    }

    @Test
    void meaningKeysIncludeEachMeaningWithAndWithoutItsOptionalPart() {
        assertEquals(Set.of("减轻", "减轻危害", "缓和"), service.meaningKeys("减轻(危害); 缓和"));
        assertEquals(Set.of("清晰", "明白易懂"), service.meaningKeys(LUCID));
    }

    @ParameterizedTest(name = "\"{0}\" for \"{1}\": distance {2}, similarity {3}")
    @CsvSource(delimiter = '|', textBlock = """
        lucid       | lucid         | 0 | 1.0
        LUCID       | lucid         | 0 | 1.0
        well known  | well-known    | 0 | 1.0
        lucud       | lucid         | 1 | 0.8
        lucdi       | lucid         | 1 | 0.8
        lucidd      | lucid         | 1 | 0.8333333333
        effect      | affect        | 1 | 0.8333333333
        prescribe   | proscribe     | 1 | 0.8888888888
        principle   | principal     | 2 | 0.7777777777
        illicit     | elicit        | 2 | 0.7142857142
        xyz         | abc           | 3 | 0.0
        """)
    void englishWordsAreComparedByTheirLetters(String typed, String expected, int distance, double similarity) {
        assertEquals(distance, service.spellingDistance(typed, expected));
        assertEquals(similarity, service.englishSimilarity(typed, expected), 1e-9);
    }
}
