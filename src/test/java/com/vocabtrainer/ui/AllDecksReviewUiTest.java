package com.vocabtrainer.ui;

import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.ReviewLog;
import com.vocabtrainer.domain.WordCard;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "All decks" on the Review tab (review finding G6): the due words of every active deck in one session. */
@Tag("ui")
class AllDecksReviewUiTest extends MainWindowUiTest {
    @Test
    void everyDeckIsReviewedAndEachCardNamesAndCountsForItsDeck() throws Exception {
        long starter = currentDeck().getId();
        dialogs.answerText("TOEFL");
        click("newDeckButton");
        long toefl = currentDeck().getId();
        WordCard quagmire = overdue(toefl, "quagmire", "困境");
        selectDeck("deckSelector", STARTER_DECK);
        selectTab("reviewTab");
        click("resetSessionButton");
        assertFalse(text("reviewWordLabel").equals("quagmire"), "one deck until All decks is chosen");

        click("reviewAllDecksToggle");

        assertEquals("quagmire", text("reviewWordLabel"));
        assertTrue(text("reviewMetaLabel").contains("| Deck: TOEFL |"), text("reviewMetaLabel"));
        assertTrue(isDisabled("newCardsPerDaySpinner"), "each deck's own limit applies");
        snapshot("all-decks");

        // Switching the header's deck does not interrupt the session.
        selectDeck("deckSelector", "TOEFL");
        assertEquals("quagmire", text("reviewWordLabel"));

        type("answerField", "困境");
        click("submitAnswerButton");
        waitForBackgroundTasks();
        click("rateGoodButton");

        List<ReviewLog> toeflLogs = services.reviewLogRepository().findByDeck(toefl);
        assertEquals(1, toeflLogs.size());
        assertEquals(quagmire.getId(), toeflLogs.get(0).getWordId());
        assertEquals(1, services.goalService().getTodayProgress(toefl).reviewedCount());
        assertEquals(0, services.goalService().getTodayProgress(starter).reviewedCount());
        assertTrue(text("reviewMetaLabel").contains("| Deck: " + STARTER_DECK + " |"), text("reviewMetaLabel"));
    }

    @Test
    void archivingTheDeckOfTheCardOnScreenShowsTheNextCard() throws Exception {
        dialogs.answerText("TOEFL");
        click("newDeckButton");
        overdue(currentDeck().getId(), "quagmire", "困境");
        selectTab("reviewTab");
        click("reviewAllDecksToggle");
        assertEquals("quagmire", text("reviewWordLabel"));
        type("answerField", "困境");
        click("submitAnswerButton");
        waitForBackgroundTasks();

        dialogs.confirm(true);
        click("archiveDeckButton");

        assertEquals(STARTER_DECK, currentDeck().getName());
        assertFalse(text("reviewWordLabel").equals("quagmire"), "the archived deck's card is gone");
        assertTrue(text("reviewMetaLabel").contains("| Deck: " + STARTER_DECK + " |"), text("reviewMetaLabel"));
        assertTrue(Fx.call(() -> find("reviewAllDecksToggle", javafx.scene.control.CheckBox.class).isSelected()));
        assertFalse(isDisabled("answerField"));
    }

    @Test
    void allDecksIsRememberedAtTheNextStart() {
        selectTab("reviewTab");
        click("reviewAllDecksToggle");

        restartApp();

        assertTrue(Fx.call(() -> find("reviewAllDecksToggle", javafx.scene.control.CheckBox.class).isSelected()));
        assertTrue(text("reviewMetaLabel").contains("| Deck: " + STARTER_DECK + " |"), text("reviewMetaLabel"));
    }

    private WordCard overdue(long deckId, String english, String chinese) throws SQLException {
        WordCard card = WordCard.createNew(deckId, english, chinese);
        card.setState(CardState.REVIEW);
        card.setStability(5);
        card.setDifficulty(5);
        card.setRepetitions(2);
        card.setIntervalDays(5);
        card.setLastReviewedAt(LocalDateTime.now().minusDays(30));
        card.setNextReviewAt(LocalDateTime.now().minusDays(25));
        return services.wordRepository().insert(card);
    }
}
