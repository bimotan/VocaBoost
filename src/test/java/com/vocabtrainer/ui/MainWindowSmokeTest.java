package com.vocabtrainer.ui;

import com.vocabtrainer.domain.Deck;
import javafx.scene.Node;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("ui")
class MainWindowSmokeTest extends MainWindowUiTest {
    @Test
    void freshDatabaseOpensOnTheStarterDeckWithTheStarterWords() throws Exception {
        Deck deck = currentDeck();
        assertEquals(STARTER_DECK, deck.getName());
        assertEquals(services.startupDeck().getId(), deck.getId());
        assertEquals("Deck: " + STARTER_DECK + " | Dictionary: starter/online fallback | AI: mock", headerSubtitle());

        assertEquals(String.valueOf(STARTER_WORDS), text("totalWordsLabel"));
        // Only the first 20 new words are due today (the default new-words-per-day limit).
        assertEquals(String.valueOf(NEW_WORDS_PER_DAY), text("dueTodayLabel"));
        assertEquals("0", text("dueReviewsLabel"));
        assertEquals(String.valueOf(NEW_WORDS_PER_DAY), text("newAvailableTodayLabel"));
        assertEquals("0 / 20", text("reviewedTodayLabel"));
        assertEquals("0 / 5", text("newWordsTodayLabel"));
        assertEquals("0%", text("accuracyTodayLabel"));
        assertEquals("0", text("masteredWordsLabel"));
        assertEquals("0 days", text("streakLabel"));
        assertEquals("0", text("xpLabel"));
        assertEquals("None yet", text("badgesLabel"));
        assertTrue(isVisible("dashboardDataFolderButton"), "the data folder is opened, not shown");

        selectTab("wordListTab");
        assertEquals(STARTER_WORDS, rowCount("wordTable"));
        selectTab("decksTab");
        assertEquals(1, rowCount("deckTable"));
        assertEquals(0, rowCount("archivedDeckTable"));
        assertEquals(STARTER_DECK, Fx.call(() -> this.<Deck>comboBox("addDeckSelector").getValue().getName()));

        // The first due card is on screen, ready for an answer.
        String question = text("reviewWordLabel");
        assertTrue(services.wordRepository().findByEnglish(deck.getId(), question).isPresent(), question);
        assertFalse(isDisabled("answerField"));
        assertFalse(isDisabled("submitAnswerButton"));
        assertTrue(isDisabled("ratingButtons"));
        assertFalse(isVisible("completionCard"));
        assertTrue(dialogs.shown().isEmpty(), "dialogs at startup: " + dialogs.shown());
    }

    @Test
    void keyControlsHaveUniqueStableIds() {
        List<String> ids = allIds();
        List<String> duplicates = ids.stream().filter(id -> Collections.frequency(ids, id) > 1).distinct().toList();
        assertTrue(duplicates.isEmpty(), "duplicate ids: " + duplicates);
        List<String> required = List.of(
            "mainTabs", "headerSubtitleLabel", "deckSelector", "newDeckButton", "renameDeckButton", "archiveDeckButton",
            "totalWordsLabel", "dueTodayLabel", "dueReviewsLabel", "newAvailableTodayLabel", "reviewedTodayLabel", "newWordsTodayLabel", "accuracyTodayLabel",
            "masteredWordsLabel", "streakLabel", "xpLabel", "badgesLabel", "reviewGoalProgress", "newWordGoalProgress",
            "editGoalsButton", "goalScopeLabel",
            "deckTable", "archivedDeckTable", "switchDeckButton", "restoreDeckButton",
            "reviewModeSelector", "sessionSizeSelector", "customSessionSizeField", "newCardsPerDaySpinner",
            "startSessionButton",
            "resetSessionButton", "sessionProgressLabel", "reviewWordLabel", "answerField", "submitAnswerButton",
            "ratingButtons", "rateAgainButton", "rateHardButton", "rateGoodButton", "rateEasyButton",
            "reviewResultArea", "completionCard",
            "addDeckSelector", "addEnglishField", "addChineseField", "addPhoneticField", "addPosField", "addTagsField",
            "addExampleArea", "addNoteArea", "addWordButton", "addWordStatusLabel",
            "importPathField", "chooseImportFileButton", "importLegacyButton", "previewCsvButton", "importCsvButton",
            "importStarterButton", "importStatusLabel",
            "statisticsCharts", "reviewCountChart", "accuracyChart", "memoryChart", "exportBackupButton",
            "importBackupButton",
            "wordTable", "wordSearchField", "wordStatusFilter", "wordTagFilterField", "wordPosFilterField",
            "offlineModeToggle", "desiredRetentionSlider", "desiredRetentionLabel", "desiredRetentionHintLabel",
            "dayRolloverHourSelector", "defaultNewCardsPerDaySpinner", "newCardsPerDayHintLabel", "goalsSummaryLabel",
            "settingsEditGoalsButton", "settingsOfflineModeToggle",
            "ecdictPathField", "chooseEcdictButton", "testEcdictButton", "saveEcdictButton", "reimportEcdictButton",
            "clearEcdictButton", "ecdictProgressBar", "cancelEcdictImportButton", "ecdictStatusLabel",
            "aiProviderField", "aiBaseUrlField", "aiApiKeyField", "aiModelField", "aiTemperatureField", "saveAiButton",
            "clearAiButton", "testAiButton", "clearAiCacheButton", "aiStatusLabel",
            "openDataFolderButton", "openLogFolderButton", "openSnapshotsFolderButton", "languageSelector"
        );
        List<String> missing = required.stream().filter(id -> !ids.contains(id)).toList();
        assertTrue(missing.isEmpty(), "missing ids: " + missing);
    }

    @Test
    void everyTabRendersAndIsSavedAsASnapshot() throws Exception {
        List<String> tabIds = List.of("dashboardTab", "decksTab", "reviewTab", "addImportTab", "statisticsTab", "wordListTab",
            "settingsTab");
        assertEquals(tabIds, Fx.call(() -> find("mainTabs", TabPane.class).getTabs().stream()
            .map(Tab::getId).toList()));

        for (String tabId : tabIds) {
            selectTab(tabId);
            assertTrue(Fx.call(() -> {
                Node content = tab(tabId).getContent();
                return tab(tabId).isSelected() && content.getScene() != null && content.isVisible();
            }), tabId);
            Path snapshot = snapshot(tabId);
            assertTrue(Files.size(snapshot) > 0, snapshot.toString());
        }

        // Selecting Statistics drew the charts for the starter deck: 7 days, no reviews yet.
        List<ChartPoint> reviewCounts = chartPoints("reviewCountChart");
        assertEquals(7, reviewCounts.size());
        assertTrue(reviewCounts.stream().allMatch(point -> point.y() == 0), reviewCounts.toString());
        assertEquals(7, chartPoints("accuracyChart").size());
        assertNotEquals("-", text("overdueStatsLabel"));
        assertEquals("No review logs yet.", text("hardestWordsArea"));
    }
}
