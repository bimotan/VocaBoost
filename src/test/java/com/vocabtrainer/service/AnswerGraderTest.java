package com.vocabtrainer.service;

import com.vocabtrainer.domain.ReviewMode;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Grading per direction (review findings A3 and E12): Chinese meanings by similarity; English words
 * by spelling, where a typo counts at most as Hard, another word as wrong and a synonym that fits
 * the prompt as right.
 */
class AnswerGraderTest {
    private final AnswerGrader grader = new AnswerGrader(new SimilarityService());
    private final List<WordCard> deck = new ArrayList<>();
    private final AnswerGrader.DeckWords deckWords = english -> deck.stream()
        .filter(word -> word.getEnglish().equalsIgnoreCase(english.trim()))
        .findFirst();

    @Test
    void aChineseAnswerIsCappedByItsSimilarity() throws SQLException {
        WordCard lucid = word("lucid", "清晰的; 明白易懂的");

        AnswerGrade match = grader.grade(lucid, ReviewMode.EN_TO_ZH, "清晰", deckWords);
        assertEquals(AnswerGrade.Verdict.MATCH, match.verdict());
        assertEquals(ReviewRating.EASY, match.maxRating());
        assertEquals(1.0, match.similarity());

        AnswerGrade synonym = grader.grade(lucid, ReviewMode.EN_TO_ZH, "清楚", deckWords);
        assertEquals(AnswerGrade.Verdict.WRONG, synonym.verdict());
        assertEquals(ReviewRating.AGAIN, synonym.maxRating(), "清楚 for lucid stays below Good");
        assertTrue(synonym.capsRatings());

        AnswerGrade close = grader.gradeMeaning("偏离常规的事", "异常的; 偏离常规的");
        assertEquals(AnswerGrade.Verdict.PARTIAL, close.verdict());
        assertEquals(ReviewRating.HARD, close.maxRating());
    }

    @Test
    void anEcdictGlossAcceptsItsMainMeaning() throws SQLException {
        WordCard abandon = word("abandon", "vt. 放弃; 抛弃\\nn. 放任");

        AnswerGrade grade = grader.grade(abandon, ReviewMode.EN_TO_ZH, "放弃", deckWords);

        assertEquals(1.0, grade.similarity());
        assertEquals(AnswerGrade.Verdict.MATCH, grade.verdict());
    }

    @ParameterizedTest(name = "\"{0}\" for {1}: {2}, at most {3}")
    @CsvSource(delimiter = '|', textBlock = """
        lucid          | lucid         | MATCH      | EASY
        ' Lucid '      | lucid         | MATCH      | EASY
        lucid.         | lucid         | MATCH      | EASY
        well known     | well-known    | MATCH      | EASY
        naive          | naïve         | MATCH      | EASY
        Cafe           | café          | MATCH      | EASY
        lucud          | lucid         | MISSPELLED | HARD
        lucdi          | lucid         | MISSPELLED | HARD
        lucidd         | lucid         | MISSPELLED | HARD
        lcd            | lucid         | WRONG      | AGAIN
        lawd           | laud          | WRONG      | AGAIN
        pusilanimus    | pusillanimous | MISSPELLED | HARD
        pusilanimuss   | pusillanimous | WRONG      | AGAIN
        ''             | lucid         | WRONG      | AGAIN
        effect         | affect        | CONFUSABLE | AGAIN
        affect         | effect        | CONFUSABLE | AGAIN
        principle      | principal     | CONFUSABLE | AGAIN
        principal      | principle     | CONFUSABLE | AGAIN
        prescribe      | proscribe     | CONFUSABLE | AGAIN
        proscribe      | prescribe     | CONFUSABLE | AGAIN
        illicit        | elicit        | CONFUSABLE | AGAIN
        elicit         | illicit       | CONFUSABLE | AGAIN
        Ingenious      | ingenuous     | CONFUSABLE | AGAIN
        forego         | forgo         | MISSPELLED | HARD
        """)
    void anEnglishAnswerIsGradedBySpelling(String typed, String expected, AnswerGrade.Verdict verdict,
                                           ReviewRating maxRating) throws SQLException {
        AnswerGrade grade = grader.grade(word(expected, "释义"), ReviewMode.ZH_TO_EN, typed, deckWords);

        assertEquals(verdict, grade.verdict());
        assertEquals(maxRating, grade.maxRating());
    }

    @Test
    void aTypoKeepsItsSimilarityButCountsAtMostAsHard() throws SQLException {
        AnswerGrade grade = grader.grade(word("lucid", "清晰的"), ReviewMode.ZH_TO_EN, "lucud", deckWords);

        assertEquals(0.8, grade.similarity(), 1e-9);
        assertEquals(ReviewRating.HARD, ReviewRating.GOOD.atMost(grade.maxRating()));
    }

    @Test
    void wellKnownConfusablesNeverPassAsGood() throws SQLException {
        String[][] pairs = {{"affect", "effect"}, {"principal", "principle"}, {"proscribe", "prescribe"},
            {"elicit", "illicit"}};
        for (String[] pair : pairs) {
            for (int typed = 0; typed < 2; typed++) {
                AnswerGrade grade = grader.grade(word(pair[1 - typed], "释义"), ReviewMode.ZH_TO_EN, pair[typed], deckWords);
                assertEquals(0.0, grade.similarity(), pair[typed]);
                assertEquals(ReviewRating.AGAIN, ReviewRating.GOOD.atMost(grade.maxRating()), pair[typed]);
                assertEquals(pair[typed], grade.otherWord());
                assertNull(grade.otherGloss());
            }
        }
    }

    @Test
    void anotherWordOfTheDeckIsWrongHoweverCloseItsSpelling() throws SQLException {
        WordCard lucid = deckWord("lucid", "清晰的; 明白易懂的");
        deckWord("lurid", "耸人听闻的; 可怕的");

        AnswerGrade grade = grader.grade(lucid, ReviewMode.ZH_TO_EN, "Lurid", deckWords);

        assertEquals(AnswerGrade.Verdict.CONFUSABLE, grade.verdict());
        assertEquals(0.0, grade.similarity());
        assertEquals(ReviewRating.AGAIN, grade.maxRating());
        assertEquals("lurid", grade.otherWord());
        assertEquals("耸人听闻的; 可怕的", grade.otherGloss());
    }

    @Test
    void anotherWordOfTheDeckThatSharesAMeaningOfThePromptIsASynonym() throws SQLException {
        WordCard capricious = deckWord("capricious", "反复无常的; 任性的");
        deckWord("mercurial", "善变的; 反复无常的");
        WordCard mitigate = deckWord("mitigate", "降低(风险、危害); 减轻");
        deckWord("alleviate", "减轻(痛苦); 缓解");

        AnswerGrade mercurial = grader.grade(capricious, ReviewMode.ZH_TO_EN, "mercurial", deckWords);
        AnswerGrade alleviate = grader.grade(mitigate, ReviewMode.ZH_TO_EN, "alleviate", deckWords);

        assertEquals(AnswerGrade.Verdict.SYNONYM, mercurial.verdict());
        assertEquals(1.0, mercurial.similarity());
        assertEquals(ReviewRating.EASY, mercurial.maxRating());
        assertEquals("mercurial", mercurial.otherWord());
        assertEquals("善变的; 反复无常的", mercurial.otherGloss());
        assertEquals(AnswerGrade.Verdict.SYNONYM, alleviate.verdict(), "减轻(痛苦) is 减轻 without its optional part");
    }

    @Test
    void aWellKnownConfusableIsWrongEvenWhenItSharesAMeaning() throws SQLException {
        WordCard affect = deckWord("affect", "影响; 假装");
        deckWord("effect", "效果; 影响");

        AnswerGrade grade = grader.grade(affect, ReviewMode.ZH_TO_EN, "effect", deckWords);

        assertEquals(AnswerGrade.Verdict.CONFUSABLE, grade.verdict());
        assertEquals(ReviewRating.AGAIN, grade.maxRating());
    }

    @Test
    void theAskedWordItselfIsAMatchNotAnotherWord() throws SQLException {
        WordCard wellKnown = deckWord("well-known", "众所周知的");

        AnswerGrade grade = grader.grade(wellKnown, ReviewMode.ZH_TO_EN, "WELL KNOWN", deckWords);

        assertEquals(AnswerGrade.Verdict.MATCH, grade.verdict());
    }

    private WordCard deckWord(String english, String chinese) {
        WordCard word = word(english, chinese);
        deck.add(word);
        return word;
    }

    private WordCard word(String english, String chinese) {
        WordCard word = WordCard.createNew(1, english, chinese);
        word.setId(english.toLowerCase(Locale.ROOT).hashCode());
        return word;
    }
}
