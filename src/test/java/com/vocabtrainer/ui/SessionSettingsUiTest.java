package com.vocabtrainer.ui;

import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.ReviewKind;
import com.vocabtrainer.domain.ReviewLog;
import com.vocabtrainer.domain.ReviewMode;
import com.vocabtrainer.domain.WordCard;
import javafx.event.ActionEvent;
import javafx.scene.control.Spinner;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Review tab's session settings: the session-size selector always shows the session's real
 * target (review findings A10, C9), the size and mode survive a restart, each deck has a
 * new-words-per-day limit (A2), and Weak Words practice leaves the schedule alone (A1).
 */
@Tag("ui")
class SessionSettingsUiTest extends MainWindowUiTest {
    @Test
    void theSessionSizeSelectorAndTheSessionTargetAlwaysAgree() {
        selectTab("reviewTab");
        assertSession("20", "20");

        selectSize("50");
        assertSession("50", "50");

        selectMode(ReviewMode.MIXED);
        assertSession("50", "50");

        click("resetSessionButton");
        assertSession("50", "50");

        dialogs.answerText("TOEFL");
        click("newDeckButton");
        assertEquals("TOEFL", currentDeck().getName());
        assertSession("50", "50");
        selectDeck("deckSelector", STARTER_DECK);
        assertSession("50", "50");
        assertEquals(ReviewMode.MIXED, selectedMode());

        selectSize("All Due");
        assertSession("All Due", "All Due");

        selectSize("Custom");
        assertFalse(isDisabled("customSessionSizeField"));
        assertEquals("20", text("customSessionSizeField"), "starts from a size that applies at once");
        assertSession("Custom", "20");
        type("customSessionSizeField", "35");
        assertSession("Custom", "35");
        type("customSessionSizeField", "");
        assertSession("Custom", "35");
        type("customSessionSizeField", "abc");
        assertEquals("", text("customSessionSizeField"), "only digits can be typed");

        type("customSessionSizeField", "12");
        click("startSessionButton");
        assertSession("Custom", "12");
        selectMode(ReviewMode.EN_TO_ZH);
        assertSession("Custom", "12");
        assertFalse(isDisabled("customSessionSizeField"));
        snapshot("custom-session-size");
    }

    @Test
    void theLastSessionSizeAndModeAreRestoredAfterARestart() {
        selectTab("reviewTab");
        selectSize("50");
        selectMode(ReviewMode.ZH_TO_EN);

        restartApp();

        selectTab("reviewTab");
        assertSession("50", "50");
        assertEquals(ReviewMode.ZH_TO_EN, selectedMode());
        assertTrue(text("reviewMetaLabel").startsWith(ReviewMode.ZH_TO_EN.getLabel() + " | "), text("reviewMetaLabel"));

        selectSize("Custom");
        type("customSessionSizeField", "35");
        restartApp();
        selectTab("reviewTab");
        assertSession("Custom", "35");
        assertEquals("35", text("customSessionSizeField"));
        assertFalse(isDisabled("customSessionSizeField"));
    }

    @Test
    void theNewWordsPerDayLimitCapsTheSessionAndTheDashboard() throws SQLException {
        selectTab("reviewTab");
        assertEquals(NEW_WORDS_PER_DAY, spinnerValue());

        setSpinnerValue(2);

        selectTab("dashboardTab");
        assertEquals("2", text("dueTodayLabel"));
        assertEquals("0", text("dueReviewsLabel"));
        assertEquals("2", text("newAvailableTodayLabel"));
        selectTab("decksTab");
        assertEquals("2", cell("deckTable", STARTER_DECK, 2));

        review(true, "rateEasyButton");
        review(true, "rateEasyButton");
        assertTrue(isVisible("completionCard"));
        assertEquals("No due words right now; today's limit of 2 new words is reached.", text("reviewMetaLabel"));
        snapshot("new-word-limit-reached");

        // Typed into the spinner and confirmed with Enter, like a user would.
        typeIntoSpinner("3");

        assertEquals(3, spinnerValue());
        assertFalse(isVisible("completionCard"));
        assertFalse(isDisabled("answerField"));
        selectTab("dashboardTab");
        assertEquals("1", text("newAvailableTodayLabel"));
        assertEquals(3, services.reviewService().newCardsPerDay(currentDeck().getId()));
        // An emptied field keeps the limit.
        selectTab("reviewTab");
        typeIntoSpinner("");
        assertEquals(3, spinnerValue());
        assertEquals("3", Fx.call(() -> find("newCardsPerDaySpinner", Spinner.class).getEditor().getText()));
    }

    @Test
    void digitsTypedIntoTheNewWordsSpinnerDoNotRateTheCard() throws SQLException {
        selectTab("reviewTab");
        WordCard word = questionWord();
        type("answerField", correctAnswer(word));
        click("submitAnswerButton");
        waitForBackgroundTasks();

        pressKey("newCardsPerDaySpinner", KeyCode.DIGIT2);

        assertFalse(isDisabled("ratingButtons"), "still waiting for a rating");
        assertTrue(services.reviewLogRepository().findByWord(word.getId()).isEmpty());
        pressKey(null, KeyCode.DIGIT3);
        assertEquals(1, services.reviewLogRepository().findByWord(word.getId()).size());
    }

    private void typeIntoSpinner(String text) {
        Fx.run(() -> {
            Spinner<?> spinner = find("newCardsPerDaySpinner", Spinner.class);
            spinner.getEditor().setText(text);
            spinner.getEditor().fireEvent(new ActionEvent());
        });
    }

    @Test
    void practicingAWeakWordThatIsNotDueLeavesItsSchedule() throws SQLException {
        dialogs.answerText("Weak");
        click("newDeckButton");
        long deckId = currentDeck().getId();
        services.wordRepository().insert(weakWord(deckId, "abate", "减弱"));
        services.wordRepository().insert(weakWord(deckId, "laud", "赞扬"));
        selectTab("reviewTab");

        selectMode(ReviewMode.WEAK_WORDS);
        WordCard first = questionWord();
        type("answerField", first.getChinese());
        click("submitAnswerButton");
        waitForBackgroundTasks();
        assertEquals("Good (3) · 3d", text("rateGoodButton"), "it stays due in 3 days");
        assertEquals("Again (1) · 1d", text("rateAgainButton"));
        snapshot("weak-word-practice");
        click("rateGoodButton");

        assertTrue(text("reviewResultArea").startsWith("Practice saved: the word was not due, so its schedule did"
            + " not change. XP +"), text("reviewResultArea"));
        WordCard stored = services.wordRepository().findById(first.getId()).orElseThrow();
        assertEquals(first.getNextReviewAt(), stored.getNextReviewAt());
        assertEquals(first.getStability(), stored.getStability());
        List<ReviewLog> logs = services.reviewLogRepository().findByWord(first.getId());
        assertEquals(ReviewKind.PRACTICE, logs.get(0).getKind());
        assertEquals(ReviewMode.EN_TO_ZH, logs.get(0).getDirection());

        review(true, "rateGoodButton");
        assertEquals("Every weak word was shown in this session.", text("reviewMetaLabel"));
        selectTab("dashboardTab");
        assertEquals("0 / 20", text("reviewedTodayLabel"), "practice is not a review");
    }

    private static WordCard weakWord(long deckId, String english, String chinese) {
        WordCard card = WordCard.createNew(deckId, english, chinese);
        card.setState(CardState.REVIEW);
        card.setStability(3);
        card.setDifficulty(6);
        card.setRepetitions(4);
        card.setConsecutiveCorrect(1);
        card.setLapses(1);
        card.setLastReviewedAt(LocalDateTime.now().minusDays(1));
        card.setNextReviewAt(LocalDateTime.now().plusDays(3));
        return card;
    }

    /** The selector shows {@code choice}, and the session counts towards {@code target}. */
    private void assertSession(String choice, String target) {
        assertEquals(choice, Fx.call(() -> this.<String>comboBox("sessionSizeSelector").getValue()));
        String progress = text("sessionProgressLabel");
        assertTrue(progress.startsWith("Session 0/" + target + " | "), "the session counts towards " + target + ": "
            + progress);
    }

    private void selectSize(String choice) {
        this.<String>select("sessionSizeSelector", choice::equals);
    }

    private void selectMode(ReviewMode mode) {
        this.<ReviewMode>select("reviewModeSelector", mode::equals);
    }

    private ReviewMode selectedMode() {
        return Fx.call(() -> this.<ReviewMode>comboBox("reviewModeSelector").getValue());
    }

    private int spinnerValue() {
        return Fx.call(() -> (Integer) find("newCardsPerDaySpinner", Spinner.class).getValue());
    }

    @SuppressWarnings("unchecked")
    private void setSpinnerValue(int value) {
        Fx.run(() -> ((Spinner<Integer>) find("newCardsPerDaySpinner", Spinner.class)).getValueFactory().setValue(value));
    }
}
