package com.vocabtrainer.ui;

import com.vocabtrainer.TestClock;
import com.vocabtrainer.app.AppServices;
import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.ReviewLog;
import com.vocabtrainer.domain.WordCard;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FSRS scheduling as the Review tab shows it: the interval on each rating button, learning steps
 * that bring a failed word back in the same session, and the response time that is logged.
 * The services and views run on a clock the tests move.
 */
@Tag("ui")
class LearningStepsUiTest extends MainWindowUiTest {
    /** Starts just after the starter words were added, so they are due as usual. */
    private final TestClock clock = new TestClock(LocalDateTime.now().plusSeconds(1));

    @Override
    AppServices.Builder configure(AppServices.Builder builder) {
        return builder.clock(clock);
    }

    @Test
    void theRatingButtonsShowWhenEachRatingBringsTheWordBack() throws SQLException {
        selectTab("reviewTab");
        WordCard word = questionWord();
        assertEquals("Good (3)", text("rateGoodButton"));
        type("answerField", correctAnswer(word));
        click("submitAnswerButton");
        waitForBackgroundTasks();

        assertEquals("Again (1) · 1m", text("rateAgainButton"));
        assertEquals("Hard (2) · 6m", text("rateHardButton"));
        assertEquals("Good (3) · 10m", text("rateGoodButton"));
        String easy = text("rateEasyButton");
        assertTrue(easy.matches("Easy \\(4\\) · 1[3-9]d"), easy);
        snapshot("rating-previews");

        click("rateEasyButton");

        WordCard saved = services.wordRepository().findById(word.getId()).orElseThrow();
        assertEquals(CardState.REVIEW, saved.getState());
        assertEquals(easy.substring(easy.indexOf('·') + 2, easy.length() - 1), String.valueOf(saved.getIntervalDays()),
            "the button showed what the rating did");
        assertEquals("Easy (4)", text("rateEasyButton"), "the next card has no answer yet");
    }

    @Test
    void aWrongAnswerShowsThatEveryRatingCountsAsAgain() {
        selectTab("reviewTab");
        type("answerField", "完全错误");
        click("submitAnswerButton");
        waitForBackgroundTasks();

        assertEquals("Again (1) · 1m", text("rateAgainButton"));
        assertEquals("Good (3) → Again (0%) · 1m", text("rateGoodButton"));
        assertEquals("Easy (4) → Again (0%) · 1m", text("rateEasyButton"));
    }

    @Test
    void aFailedWordComesBackInTheSameSessionOnceItsStepIsDue() throws SQLException {
        WordCard failed = review(false, "rateAgainButton");
        WordCard second = review(true, "rateGoodButton");
        assertTrue(!failed.getEnglish().equals(text("reviewWordLabel")), "not before its one-minute step is over");

        clock.advance(Duration.ofMinutes(2));
        WordCard third = review(true, "rateEasyButton");

        assertEquals(failed.getEnglish(), text("reviewWordLabel"), "after " + second.getEnglish() + " and "
            + third.getEnglish());
        assertTrue(text("reviewMetaLabel").startsWith("English → Chinese | Learning | Recall "), text("reviewMetaLabel"));
        assertTrue(text("sessionProgressLabel").startsWith("Session 3/20 | "), text("sessionProgressLabel"));
    }

    @Test
    void theLoggedResponseTimeRunsFromShowingTheWordToSubmittingTheAnswer() throws SQLException {
        selectTab("reviewTab");
        WordCard word = questionWord();

        clock.advance(Duration.ofSeconds(7));
        type("answerField", correctAnswer(word));
        click("submitAnswerButton");
        waitForBackgroundTasks();
        clock.advance(Duration.ofSeconds(20));
        click("rateGoodButton");

        List<ReviewLog> logs = services.reviewLogRepository().findByWord(word.getId());
        assertEquals(1, logs.size());
        assertEquals(7_000, logs.get(0).getElapsedMillis());
    }

    @Test
    void timeSpentOnOtherTabsIsNotPartOfTheResponseTime() throws SQLException {
        // The first card was loaded at startup, behind the Dashboard.
        clock.advance(Duration.ofMinutes(15));
        selectTab("reviewTab");
        WordCard word = questionWord();
        clock.advance(Duration.ofSeconds(5));
        selectTab("wordListTab");
        clock.advance(Duration.ofMinutes(30));
        selectTab("reviewTab");
        clock.advance(Duration.ofSeconds(2));

        type("answerField", correctAnswer(word));
        click("submitAnswerButton");
        waitForBackgroundTasks();
        click("rateGoodButton");

        List<ReviewLog> logs = services.reviewLogRepository().findByWord(word.getId());
        assertEquals(1, logs.size());
        assertEquals(7_000, logs.get(0).getElapsedMillis());
    }
}
