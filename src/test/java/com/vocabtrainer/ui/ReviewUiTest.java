package com.vocabtrainer.ui;

import com.vocabtrainer.app.AppServices;
import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.ReviewLog;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.ReviewLogRepository;
import javafx.scene.control.ProgressBar;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("ui")
class ReviewUiTest extends MainWindowUiTest {
    private FailingReviewLogRepository reviewLogs;

    @Override
    AppServices.Builder configure(AppServices.Builder builder) {
        return builder.reviewLogRepository(databaseManager -> reviewLogs = new FailingReviewLogRepository(databaseManager));
    }

    @Test
    void typingTheCorrectAnswerAndRatingGoodSavesTheReviewAndShowsTheNextCard() throws Exception {
        selectTab("reviewTab");
        click("startSessionButton");
        assertEquals("Session 0/20 | Accuracy 0% | XP 0", text("sessionProgressLabel"));

        WordCard word = questionWord();
        String answer = correctAnswer(word);
        assertEquals("英译中 | New | Lapses 0", text("reviewMetaLabel"));
        type("answerField", answer);
        click("submitAnswerButton");

        assertTrue(text("reviewResultArea").startsWith("Correct answer: " + word.getChinese()
            + System.lineSeparator() + "Your answer: " + answer
            + System.lineSeparator() + "Answer similarity: 100%"), text("reviewResultArea"));
        assertFalse(isDisabled("ratingButtons"));
        assertTrue(isDisabled("submitAnswerButton"));
        assertTrue(isDisabled("answerField"));
        waitForBackgroundTasks();

        click("rateGoodButton");

        List<ReviewLog> logs = services.reviewLogRepository().findByDeck(deckId());
        assertEquals(1, logs.size());
        assertEquals(word.getId(), logs.get(0).getWordId());
        assertEquals(ReviewRating.GOOD, logs.get(0).getRating());
        assertEquals(answer, logs.get(0).getUserAnswer());
        assertEquals(1.0, logs.get(0).getSimilarity());
        WordCard saved = services.wordRepository().findById(word.getId()).orElseThrow();
        assertEquals(1, saved.getRepetitions());
        assertEquals(0, saved.getLapses());

        assertTrue(text("sessionProgressLabel").startsWith("Session 1/20 | Accuracy 100% | XP "), text("sessionProgressLabel"));
        selectTab("dashboardTab");
        assertEquals("1 / 20", text("reviewedTodayLabel"));
        assertEquals("100%", text("accuracyTodayLabel"));
        assertEquals(String.valueOf(services.goalService().totalXp(deckId())), text("xpLabel"));
        assertNotEquals("0", text("xpLabel"));
        assertEquals(0.05, Fx.call(() -> find("reviewGoalProgress", ProgressBar.class).getProgress()), 1e-9);
        assertEquals(String.valueOf(STARTER_WORDS - 1), text("dueTodayLabel"));

        // The next card is ready for a new answer.
        selectTab("reviewTab");
        assertNotEquals(word.getEnglish(), text("reviewWordLabel"));
        assertTrue(text("reviewResultArea").startsWith("Saved. XP +"), text("reviewResultArea"));
        assertEquals("", text("answerField"));
        assertFalse(isDisabled("answerField"));
        assertFalse(isDisabled("submitAnswerButton"));
        assertTrue(isDisabled("ratingButtons"));
    }

    @Test
    void pressingEnterSubmitsAndAWrongAnswerRatedAgainRestartsTheLearningSteps() throws Exception {
        selectTab("reviewTab");
        WordCard word = questionWord();
        type("answerField", "完全错误");
        pressEnter("answerField");

        assertTrue(text("reviewResultArea").startsWith("Correct answer: " + word.getChinese()), text("reviewResultArea"));
        assertTrue(isDisabled("answerField"));
        waitForBackgroundTasks();
        click("rateAgainButton");

        List<ReviewLog> logs = services.reviewLogRepository().findByDeck(deckId());
        assertEquals(1, logs.size());
        assertEquals(ReviewRating.AGAIN, logs.get(0).getRating());
        assertEquals("完全错误", logs.get(0).getUserAnswer());
        WordCard saved = services.wordRepository().findById(word.getId()).orElseThrow();
        // A new word that is not known yet is learning, not a lapse; it is asked again in a minute.
        assertEquals(CardState.LEARNING, saved.getState());
        assertEquals(0, saved.getLearningStep());
        assertEquals(0, saved.getLapses());
        assertEquals(saved.getLastReviewedAt().plusMinutes(1), saved.getNextReviewAt());
        selectTab("dashboardTab");
        assertEquals("1 / 20", text("reviewedTodayLabel"));
        assertEquals("0%", text("accuracyTodayLabel"));
    }

    @Test
    void aRatingThatFailsToSaveKeepsTheAnswerOnScreenAndCanBeRetried() throws Exception {
        selectTab("reviewTab");
        WordCard word = questionWord();
        type("answerField", correctAnswer(word));
        click("submitAnswerButton");
        waitForBackgroundTasks();
        String result = text("reviewResultArea");

        reviewLogs.failNextInsert = true;
        click("rateGoodButton");

        ScriptedDialogs.Shown error = dialogs.takeError();
        assertEquals("Rating not saved - choose a rating again to retry", error.title());
        assertTrue(error.content().contains("simulated lock"), error.content());
        assertTrue(services.reviewLogRepository().findByDeck(deckId()).isEmpty());
        WordCard unchanged = services.wordRepository().findById(word.getId()).orElseThrow();
        assertEquals(0, unchanged.getRepetitions());
        selectTab("dashboardTab");
        assertEquals("0 / 20", text("reviewedTodayLabel"));
        // Same card, same answer, and the rating buttons are armed again.
        selectTab("reviewTab");
        assertEquals(word.getEnglish(), text("reviewWordLabel"));
        assertEquals(result, text("reviewResultArea"));
        assertEquals(correctAnswer(word), text("answerField"));
        assertFalse(isDisabled("ratingButtons"));
        assertTrue(isDisabled("submitAnswerButton"));

        click("rateGoodButton");

        List<ReviewLog> logs = services.reviewLogRepository().findByDeck(deckId());
        assertEquals(1, logs.size());
        assertEquals(ReviewRating.GOOD, logs.get(0).getRating());
        assertEquals(1.0, logs.get(0).getSimilarity());
        WordCard saved = services.wordRepository().findById(word.getId()).orElseThrow();
        assertEquals(1, saved.getRepetitions());
        assertEquals(0, saved.getLapses());
        selectTab("dashboardTab");
        assertEquals("1 / 20", text("reviewedTodayLabel"));
        assertNotEquals(word.getEnglish(), text("reviewWordLabel"));
    }

    @Test
    void aCustomSessionShowsTheCompletionCardWhenItsTargetIsReached() throws Exception {
        selectTab("reviewTab");
        assertTrue(isDisabled("customSessionSizeField"));
        Fx.run(() -> this.<String>comboBox("sessionSizeSelector").getSelectionModel().select("Custom"));
        assertFalse(isDisabled("customSessionSizeField"));
        type("customSessionSizeField", "1");
        click("startSessionButton");
        assertEquals("Session 0/1 | Accuracy 0% | XP 0", text("sessionProgressLabel"));

        review(true, "rateEasyButton");

        assertEquals("Review complete", text("reviewWordLabel"));
        assertEquals("Session target reached.", text("reviewMetaLabel"));
        assertTrue(isVisible("completionCard"));
        assertEquals("Session Complete", text("completionTitleLabel"));
        assertTrue(text("completionMetricsLabel").startsWith("Completed: 1/1 | Accuracy: 100% | XP: "),
            text("completionMetricsLabel"));
        assertTrue(isDisabled("answerField"));
        assertTrue(isDisabled("submitAnswerButton"));
        assertTrue(isDisabled("ratingButtons"));

        click("resetSessionButton");
        assertFalse(isVisible("completionCard"));
        assertFalse(isDisabled("answerField"));
    }

    @Test
    void anInvalidCustomSessionSizeIsReported() {
        selectTab("reviewTab");
        Fx.run(() -> this.<String>comboBox("sessionSizeSelector").getSelectionModel().select("Custom"));
        type("customSessionSizeField", "0");
        click("startSessionButton");

        ScriptedDialogs.Shown error = dialogs.takeError();
        assertEquals("Start session failed", error.title());
        assertEquals("Custom session size must be between 1 and 500.", error.content());
    }

    private long deckId() {
        return currentDeck().getId();
    }

    /** Fails the next review-log insert, as a locked database would. */
    static final class FailingReviewLogRepository extends ReviewLogRepository {
        volatile boolean failNextInsert;

        FailingReviewLogRepository(DatabaseManager databaseManager) {
            super(databaseManager);
        }

        @Override
        public ReviewLog insert(ReviewLog log) throws SQLException {
            if (failNextInsert) {
                failNextInsert = false;
                throw new SQLException("[SQLITE_BUSY] simulated lock while inserting review log");
            }
            return super.insert(log);
        }
    }
}
