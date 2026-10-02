package com.vocabtrainer.ui;

import com.vocabtrainer.domain.ReviewLog;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;
import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The review loop works from the keyboard: Enter submits, then 1-4 or Space rate (review finding C6). */
@Tag("ui")
class ReviewShortcutsUiTest extends MainWindowUiTest {
    @Test
    void theRatingButtonsShowTheirKeys() {
        assertEquals("Again (1)", text("rateAgainButton"));
        assertEquals("Hard (2)", text("rateHardButton"));
        assertEquals("Good (3)", text("rateGoodButton"));
        assertEquals("Easy (4)", text("rateEasyButton"));
    }

    @Test
    void enterThenANumberKeyAnswersAndRatesACard() throws SQLException {
        WordCard word = answerWithEnter(true);
        assertEquals("rateGoodButton", focusOwnerId());

        pressKey(null, KeyCode.DIGIT3);

        List<ReviewLog> logs = logs();
        assertEquals(1, logs.size());
        assertEquals(word.getId(), logs.get(0).getWordId());
        assertEquals(ReviewRating.GOOD, logs.get(0).getRating());
        assertNotEquals(word.getEnglish(), text("reviewWordLabel"));
        assertTrue(text("reviewResultArea").startsWith("Saved. XP +"), text("reviewResultArea"));
        assertEquals("answerField", focusOwnerId());
        // The key rated the last card; it must not also type into the next one.
        assertEquals("", text("answerField"));
    }

    @Test
    void eachKeyGivesItsRating() throws SQLException {
        List<KeyCode> keys = List.of(KeyCode.DIGIT1, KeyCode.NUMPAD2, KeyCode.SPACE, KeyCode.DIGIT4, KeyCode.DIGIT2);
        for (KeyCode key : keys) {
            answerWithEnter(key != KeyCode.DIGIT1);
            pressKey(null, key);
            assertEquals("", text("answerField"), key + " reached the next card");
        }

        assertEquals(List.of(ReviewRating.AGAIN, ReviewRating.HARD, ReviewRating.GOOD, ReviewRating.EASY, ReviewRating.HARD),
            logs().stream().map(ReviewLog::getRating).toList());
    }

    @Test
    void numberKeysAreTextUntilTheAnswerIsSubmitted() throws SQLException {
        selectTab("reviewTab");
        WordCard word = questionWord();

        pressKey("answerField", KeyCode.DIGIT1);
        pressKey("answerField", KeyCode.SPACE);

        assertEquals("1 ", text("answerField"));
        assertTrue(logs().isEmpty());
        assertEquals(word.getEnglish(), text("reviewWordLabel"));
        assertFalse(isDisabled("submitAnswerButton"));
        assertTrue(isDisabled("ratingButtons"));
    }

    @Test
    void numberKeysDoNotRateWhileAnotherTabIsShown() throws SQLException {
        WordCard word = answerWithEnter(true);
        selectTab("dashboardTab");

        pressKey(null, KeyCode.DIGIT4);

        assertTrue(logs().isEmpty());
        selectTab("reviewTab");
        assertEquals(word.getEnglish(), text("reviewWordLabel"));
        pressKey(null, KeyCode.DIGIT4);
        assertEquals(ReviewRating.EASY, logs().get(0).getRating());
    }

    @Test
    void numberKeysTypedIntoAnotherFieldAreNotRatings() throws SQLException {
        Fx.run(() -> this.<String>comboBox("sessionSizeSelector").getSelectionModel().select("Custom"));
        answerWithEnter(true);

        pressKey("customSessionSizeField", KeyCode.DIGIT2);

        assertTrue(logs().isEmpty());
        assertFalse(isDisabled("ratingButtons"));
    }

    @Test
    void spaceOnAFocusedRatingButtonPressesThatButton() throws SQLException {
        answerWithEnter(false);
        Fx.run(() -> find("rateAgainButton", Node.class).requestFocus());

        pressKey(null, KeyCode.SPACE);

        assertEquals(List.of(ReviewRating.AGAIN), logs().stream().map(ReviewLog::getRating).toList());
        assertEquals("", text("answerField"));
        assertEquals("answerField", focusOwnerId());
    }

    @Test
    void aRatingKeyWhoseTypedEventWentElsewhereDoesNotEatTheNextCharacter() throws SQLException {
        answerWithEnter(true);
        // The key's typed and released events never reach the window, e.g. because the rating
        // opened an error dialog that took them.
        pressKeyDown(null, KeyCode.DIGIT3);
        assertEquals(1, logs().size());

        pressKey(null, KeyCode.A);

        assertEquals("a", text("answerField"));
    }

    /** Types an answer for the card on the Review tab and presses Enter; returns the card. */
    private WordCard answerWithEnter(boolean correct) throws SQLException {
        selectTab("reviewTab");
        WordCard word = questionWord();
        type("answerField", correct ? correctAnswer(word) : "完全错误");
        pressEnter("answerField");
        waitForBackgroundTasks();
        assertFalse(isDisabled("ratingButtons"));
        return word;
    }

    private List<ReviewLog> logs() throws SQLException {
        return services.reviewLogRepository().findByDeck(currentDeck().getId());
    }
}
