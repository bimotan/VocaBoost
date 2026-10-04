package com.vocabtrainer.ui;

import com.vocabtrainer.app.AppServices;
import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.DictionaryEntry;
import com.vocabtrainer.domain.ReviewKind;
import com.vocabtrainer.domain.ReviewLog;
import com.vocabtrainer.domain.ReviewMode;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.service.AiServiceFactory;
import com.vocabtrainer.service.DictionaryServiceFactory;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One learner's way through the merged features, on the app's own dictionary chain and AI
 * composition with offline mode on (so nothing reaches the network): look a word up in the offline
 * dictionaries and add it to a new deck, review it by typing and with the keyboard, override the
 * answer check, see the Dashboard and Statistics agree, and restore the deck's backup into another
 * deck with its FSRS state and review logs intact.
 */
@Tag("ui")
class EndToEndUiTest extends MainWindowUiTest {
    @Override
    AppServices.Builder configure(AppServices.Builder builder) {
        // The app's wiring, except that no AI provider is configured from the environment.
        return builder
            .dictionaryService((cache, local) -> DictionaryServiceFactory.create(cache, local, Map.of(),
                clock, () -> services.settingsService().isOfflineMode()))
            .aiService((cache, settings) -> AiServiceFactory.create(cache, settings, Map.of()));
    }

    @Test
    void aWordLookedUpOfflineIsReviewedAndItsBackupRestoresItsMemory() throws Exception {
        click("offlineModeToggle");
        assertTrue(headerSubtitle().endsWith("| Offline mode"), headerSubtitle());
        dialogs.answerText("E2E");
        click("newDeckButton");
        Deck deck = currentDeck();
        assertEquals("E2E", deck.getName());

        // Look the word up in the offline dictionaries and add it from the result.
        selectTab("addImportTab");
        type("lookupField", "lucid");
        click("lookupButton");
        waitForBackgroundTasks();
        Fx.run(() -> {
            @SuppressWarnings("unchecked")
            ListView<DictionaryEntry> results = find("lookupResults", ListView.class);
            assertEquals(1, results.getItems().size(), results.getItems().toString());
            results.getSelectionModel().select(0);
        });
        assertEquals("lucid", text("addEnglishField"));
        assertEquals("清晰的; 明白易懂的", text("addChineseField"));
        // The starter deck has lucid too; the new deck gets a card of its own.
        dialogs.chooseButton("Add as typed");
        click("addWordButton");
        waitForTextStartingWith("addWordStatusLabel", "Added to E2E: lucid");
        assertEquals("lucid is already in " + STARTER_DECK, dialogs.last(ScriptedDialogs.Kind.CHOOSE).header());
        WordCard added = services.wordRepository().findByEnglish(deck.getId(), "lucid").orElseThrow();
        assertEquals(CardState.NEW, added.getState());
        assertEquals(TEST_START, added.getAddedAt(), "dated by the app's clock");
        assertEquals(TEST_START, added.getNextReviewAt());
        assertFalse(added.getTags().contains("UNCHECKED"), added.getTags());
        selectTab("dashboardTab");
        assertEquals("1", text("totalWordsLabel"));
        assertEquals("0", text("xpLabel"), "adding a word earns no XP");

        // A wrong answer: every rating counts as Again until the user says "I was right".
        selectTab("reviewTab");
        assertEquals("lucid", text("reviewWordLabel"));
        type("answerField", "模糊的");
        pressEnter("answerField");
        waitForBackgroundTasks();
        assertTrue(text("reviewResultArea").startsWith("Correct answer: 清晰的; 明白易懂的"), text("reviewResultArea"));
        assertTrue(text("reviewResultArea").contains("Mock AI"), text("reviewResultArea"));
        assertEquals("rateAgainButton", focusOwnerId());
        assertTrue(text("rateGoodButton").matches("Good \\(3\\) → Again \\(\\d+%\\) · 1m"), text("rateGoodButton"));
        click("overrideButton");
        assertEquals("Good (3) · 10m", text("rateGoodButton"));
        assertEquals("rateGoodButton", focusOwnerId());
        pressKey(null, KeyCode.DIGIT3);

        // The learning step brings the card back within the learn-ahead window; this time, half a
        // minute later, it is right.
        waitForText("reviewWordLabel", "lucid");
        clock.advance(Duration.ofSeconds(30));
        assertTrue(text("reviewMetaLabel").contains(" | Learning | "), text("reviewMetaLabel"));
        assertEquals("answerField", focusOwnerId());
        type("answerField", "清晰的");
        pressEnter("answerField");
        waitForBackgroundTasks();
        assertEquals("rateGoodButton", focusOwnerId());
        assertTrue(text("rateGoodButton").matches("Good \\(3\\) · \\d+d"), text("rateGoodButton"));
        assertTrue(text("rateEasyButton").matches("Easy \\(4\\) · \\d+d"), text("rateEasyButton"));
        String goodInterval = text("rateGoodButton");
        pressKey(null, KeyCode.SPACE);
        waitForText("reviewWordLabel", "Review complete");

        WordCard reviewed = services.wordRepository().findByEnglish(deck.getId(), "lucid").orElseThrow();
        assertEquals(CardState.REVIEW, reviewed.getState());
        assertTrue(reviewed.getStability() > 0 && reviewed.getDifficulty() > 0, reviewed.toString());
        List<ReviewLog> logs = services.reviewLogRepository().findByDeck(deck.getId());
        assertEquals(2, logs.size());
        ReviewLog overridden = logs.get(0);
        assertEquals(ReviewRating.GOOD, overridden.getRating());
        assertEquals(ReviewRating.GOOD, overridden.getEffectiveRating());
        assertTrue(overridden.isOverridden());
        assertEquals(ReviewKind.LEARN, overridden.getKind());
        assertEquals(ReviewMode.EN_TO_ZH, overridden.getDirection());
        assertEquals(ReviewKind.REVIEW, logs.get(1).getKind());
        assertTrue(goodInterval.endsWith(" · " + intervalDays(reviewed) + "d"), goodInterval + " vs " + reviewed);

        // Dashboard and Statistics count the same two correct reviews and one new word.
        selectTab("dashboardTab");
        assertEquals("2 / 20", text("reviewedTodayLabel"));
        assertEquals("1 / 5", text("newWordsTodayLabel"));
        assertEquals("100%", text("accuracyTodayLabel"));
        assertEquals("0", text("dueTodayLabel"));
        int xp = Integer.parseInt(text("xpLabel"));
        assertTrue(xp > 0);
        selectTab("statisticsTab");
        List<ChartPoint> reviewCounts = chartPoints("reviewCountChart");
        assertEquals(2, reviewCounts.get(reviewCounts.size() - 1).y());
        List<ChartPoint> accuracy = chartPoints("accuracyChart");
        assertEquals(1.0, accuracy.get(accuracy.size() - 1).y(), 1e-9);

        // The review log CSV says what each review counted as.
        Path logsCsv = tempDir.resolve("logs.csv");
        dialogs.saveFile(logsCsv);
        click("exportReviewLogsCsvButton");
        waitForBackgroundTasks();
        List<String> csv = Files.readAllLines(logsCsv, StandardCharsets.UTF_8);
        assertEquals(3, csv.size());
        assertTrue(csv.get(0).endsWith(",effective_rating,overridden,kind,direction"), csv.get(0));
        assertTrue(csv.get(1).endsWith(",GOOD,true,LEARN,EN_TO_ZH"), csv.get(1));

        // Back up the deck and restore it into a new one: the memory and the logs come back as they were.
        Path backup = tempDir.resolve("backup.json");
        dialogs.saveFile(backup);
        click("exportBackupButton");
        waitForBackgroundTasks();
        dialogs.answerText("Restored");
        click("newDeckButton");
        Deck restoredDeck = currentDeck();
        selectTab("statisticsTab");
        dialogs.openFile(backup).chooseButton("Keep current progress");
        click("importBackupButton");
        waitForBackgroundTasks();
        assertTrue(dialogs.last(ScriptedDialogs.Kind.TEXT).content().contains("Review logs: 2 added"),
            dialogs.last(ScriptedDialogs.Kind.TEXT).content());

        WordCard restored = services.wordRepository().findByEnglish(restoredDeck.getId(), "lucid").orElseThrow();
        assertEquals(reviewed.getState(), restored.getState());
        assertEquals(reviewed.getStability(), restored.getStability(), 1e-9);
        assertEquals(reviewed.getDifficulty(), restored.getDifficulty(), 1e-9);
        assertEquals(reviewed.getLearningStep(), restored.getLearningStep());
        assertEquals(reviewed.getNextReviewAt(), restored.getNextReviewAt());
        assertEquals(reviewed.getLastReviewedAt(), restored.getLastReviewedAt());
        List<ReviewLog> restoredLogs = services.reviewLogRepository().findByDeck(restoredDeck.getId());
        assertEquals(2, restoredLogs.size());
        assertTrue(restoredLogs.get(0).isOverridden());
        assertEquals(ReviewKind.LEARN, restoredLogs.get(0).getKind());
        assertEquals(ReviewMode.EN_TO_ZH, restoredLogs.get(0).getDirection());

        // The restored deck shows the same day: its history, not new XP.
        selectTab("dashboardTab");
        assertEquals("2 / 20", text("reviewedTodayLabel"));
        assertEquals("1 / 5", text("newWordsTodayLabel"));
        assertEquals(String.valueOf(xp), text("xpLabel"));
        assertEquals("0", text("dueTodayLabel"));
        selectTab("reviewTab");
        assertEquals("Review complete", text("reviewWordLabel"));
        snapshot("restored");
    }

    private static long intervalDays(WordCard word) {
        return word.getIntervalDays();
    }
}
