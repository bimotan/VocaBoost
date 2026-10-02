package com.vocabtrainer.ui;

import com.vocabtrainer.domain.ReviewLog;
import com.vocabtrainer.domain.ReviewMode;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * After an answer is checked, the rating buttons say what each rating counts as, the suggested one
 * has the focus, and "I was right" lets the chosen rating count (review findings A4 and C4).
 */
@Tag("ui")
class AnswerCheckUiTest extends MainWindowUiTest {
    @Test
    void aWrongAnswerShowsWhatEachRatingCountsAsAndSuggestsAgain() throws SQLException {
        answer("完全错误");

        assertEquals("Again (1) · 1m", text("rateAgainButton"));
        assertEquals("Hard (2) → Again (0%) · 1m", text("rateHardButton"));
        assertEquals("Good (3) → Again (0%) · 1m", text("rateGoodButton"));
        assertEquals("Easy (4) → Again (0%) · 1m", text("rateEasyButton"));
        assertEquals("rateAgainButton", focusOwnerId());
        assertFalse(isDisabled("overrideButton"));
        assertFalse(isOverrideSelected());
        assertTrue(text("reviewResultArea").contains("Does not match: counts as Again."), text("reviewResultArea"));
        snapshot("capped");
    }

    @Test
    void iWasRightCountsTheRatingTheUserChooses() throws SQLException {
        WordCard word = answer("完全错误");

        click("overrideButton");

        assertTrue(isOverrideSelected());
        assertEquals("Good (3) · 10m", text("rateGoodButton"));
        assertEquals("Hard (2) · 6m", text("rateHardButton"));
        assertEquals("rateGoodButton", focusOwnerId());
        snapshot("overridden");

        pressKey(null, KeyCode.SPACE);

        ReviewLog log = onlyLog();
        assertEquals(word.getId(), log.getWordId());
        assertEquals(ReviewRating.GOOD, log.getRating());
        assertEquals(ReviewRating.GOOD, log.getEffectiveRating());
        assertTrue(log.isOverridden());
        assertTrue(text("reviewResultArea").startsWith("Saved as Good: you overrode the answer check. XP +"),
            text("reviewResultArea"));
        assertTrue(text("sessionProgressLabel").startsWith("Session 1/20 | Accuracy 100% | XP "),
            text("sessionProgressLabel"));
        assertFalse(isOverrideSelected(), "the next card starts without an override");
        assertTrue(isDisabled("overrideButton"));
    }

    @Test
    void takingTheOverrideBackCapsTheRatingsAgain() throws SQLException {
        answer("完全错误");
        click("overrideButton");

        click("overrideButton");

        assertFalse(isOverrideSelected());
        assertEquals("Good (3) → Again (0%) · 1m", text("rateGoodButton"));
        assertEquals("rateAgainButton", focusOwnerId());
        click("rateGoodButton");
        assertEquals(ReviewRating.AGAIN, onlyLog().getEffectiveRating());
        assertFalse(onlyLog().isOverridden());
        assertTrue(text("reviewResultArea").startsWith("Saved as Again. XP +"), text("reviewResultArea"));
    }

    @Test
    void afterIWasRightTheSuggestedRatingHasTheFocusEvenWhenItIsTheSame() throws SQLException {
        selectTab("reviewTab");
        WordCard word = questionWord();
        word.setChinese("明白易懂的");
        services.wordRepository().update(word);
        // Close but not equal (83%): Easy counts as Good, and Good stays the suggestion either way.
        answer("明白易懂了");
        assertEquals("rateGoodButton", focusOwnerId());
        assertTrue(text("rateEasyButton").startsWith("Easy (4) → Good (83%) · "), text("rateEasyButton"));

        // A mouse click focuses the toggle before firing it.
        Fx.run(() -> find("overrideButton", ToggleButton.class).requestFocus());
        click("overrideButton");

        assertTrue(isOverrideSelected());
        assertEquals("rateGoodButton", focusOwnerId(), "Space must not toggle \"I was right\" back");
        pressKey(null, KeyCode.SPACE);
        ReviewLog log = onlyLog();
        assertEquals(ReviewRating.GOOD, log.getEffectiveRating());
        assertFalse(log.isOverridden(), "Good was not capped, so the override changed nothing");
        assertTrue(text("reviewResultArea").startsWith("Saved. XP +"), text("reviewResultArea"));
    }

    @Test
    void aMatchingAnswerSuggestsGoodAndHasNothingToOverride() throws SQLException {
        selectTab("reviewTab");
        answer(correctAnswer(questionWord()));

        assertEquals("Good (3) · 10m", text("rateGoodButton"));
        assertEquals("rateGoodButton", focusOwnerId());
        assertTrue(isDisabled("overrideButton"));
    }

    @Test
    void anotherDeckWordThatFitsTheChinesePromptIsAcceptedAsASynonym() throws SQLException {
        long deckId = currentDeck().getId();
        WordCard abate = services.wordRepository().findByEnglish(deckId, "abate").orElseThrow();
        services.wordRepository().insert(WordCard.createNew(deckId, "diminish", "减少; 缩小"));
        selectTab("reviewTab");
        this.<ReviewMode>select("reviewModeSelector", mode -> mode == ReviewMode.ZH_TO_EN);
        assertEquals(abate.getChinese(), text("reviewWordLabel"));

        type("answerField", "diminish");
        pressEnter("answerField");
        waitForBackgroundTasks();

        assertTrue(text("reviewResultArea").contains("Accepted as a synonym: \"diminish\" (减少; 缩小) also fits"
            + " this prompt."), text("reviewResultArea"));
        assertTrue(text("reviewResultArea").contains("Answer similarity: 100%"), text("reviewResultArea"));
        assertEquals("Good (3) · 10m", text("rateGoodButton"));
        assertTrue(isDisabled("overrideButton"));
        pressKey(null, KeyCode.DIGIT3);
        ReviewLog log = onlyLog();
        assertEquals(abate.getId(), log.getWordId());
        assertEquals(ReviewRating.GOOD, log.getEffectiveRating());
        assertEquals("diminish", log.getUserAnswer());
    }

    /** Types {@code text} for the card on the Review tab and submits it; returns the card. */
    private WordCard answer(String text) throws SQLException {
        selectTab("reviewTab");
        WordCard word = questionWord();
        type("answerField", text);
        pressEnter("answerField");
        waitForBackgroundTasks();
        assertFalse(isDisabled("ratingButtons"));
        return word;
    }

    private boolean isOverrideSelected() {
        return Fx.call(() -> find("overrideButton", ToggleButton.class).isSelected());
    }

    private ReviewLog onlyLog() throws SQLException {
        List<ReviewLog> logs = services.reviewLogRepository().findByDeck(currentDeck().getId());
        assertEquals(1, logs.size());
        return logs.get(0);
    }
}
