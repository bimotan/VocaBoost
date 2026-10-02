package com.vocabtrainer.ui;

import com.vocabtrainer.TestClock;
import com.vocabtrainer.app.AppServices;
import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.service.SettingsService;
import javafx.event.ActionEvent;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Settings tab: the scheduler's desired retention and day rollover, the default new words per
 * day, the goals, offline mode, and the dictionary, AI and folder controls moved there from the
 * Add / Import and Statistics tabs. Every setting applies at once and is kept across a restart.
 */
@Tag("ui")
class SettingsUiTest extends MainWindowUiTest {
    /** Tomorrow at 10 am: after the starter words were added, at an hour the rollover tests can rely on. */
    private static final LocalDateTime NOW = LocalDate.now().plusDays(1).atTime(10, 0);
    private static final Pattern DAYS = Pattern.compile("· (\\d+)d$");

    private final TestClock clock = new TestClock(NOW);

    @Override
    AppServices.Builder configure(AppServices.Builder builder) {
        return builder.clock(clock);
    }

    @Test
    void aHigherRetentionShortensTheIntervalsTheRatingButtonsShowAndGive() throws SQLException {
        selectTab("reviewTab");
        WordCard word = questionWord();
        type("answerField", correctAnswer(word));
        click("submitAnswerButton");
        waitForBackgroundTasks();
        int atDefault = days(text("rateEasyButton"));

        selectTab("settingsTab");
        assertEquals("90%", text("desiredRetentionLabel"));
        assertTrue(text("desiredRetentionHintLabel").startsWith("Higher = more reviews"), text("desiredRetentionHintLabel"));
        // A drag is saved when the thumb is let go.
        Fx.run(() -> {
            Slider slider = find("desiredRetentionSlider", Slider.class);
            slider.setValueChanging(true);
            slider.setValue(0.95);
        });
        assertEquals("95%", text("desiredRetentionLabel"));
        assertEquals(0.9, services.reviewScheduler().options().desiredRetention(), "not saved while dragging");
        Fx.run(() -> find("desiredRetentionSlider", Slider.class).setValueChanging(false));

        assertEquals(0.95, services.reviewScheduler().options().desiredRetention());
        assertEquals("0.95", services.settingsService().get(SettingsService.DESIRED_RETENTION_KEY).orElseThrow());
        assertTrue(text("desiredRetentionHintLabel").contains(
            "At 95%, a word that 90% brings back after 10 days comes back after 5 days."), text("desiredRetentionHintLabel"));
        snapshot("study-settings");
        selectTab("statisticsTab");
        assertTrue(text("analyticsArea").contains("when the chance of recall drops to 95%"), text("analyticsArea"));

        // The answered card's buttons show the new intervals, and the rating gives what they show.
        selectTab("reviewTab");
        int atHigher = days(text("rateEasyButton"));
        assertTrue(atHigher < atDefault, atHigher + " days at 95%, " + atDefault + " at 90%");
        click("rateEasyButton");
        assertEquals(atHigher, services.wordRepository().findById(word.getId()).orElseThrow().getIntervalDays());
    }

    @Test
    void anEarlierDayRolloverLeavesAWordDueAfterMidnightForTomorrow() throws SQLException {
        WordCard dueAtTwo = WordCard.createNew(currentDeck().getId(), "obfuscate", "使模糊");
        dueAtTwo.setState(CardState.REVIEW);
        dueAtTwo.setStability(10);
        dueAtTwo.setDifficulty(5);
        dueAtTwo.setRepetitions(2);
        dueAtTwo.setConsecutiveCorrect(2);
        dueAtTwo.setIntervalDays(10);
        dueAtTwo.setLastReviewedAt(NOW.minusDays(10));
        dueAtTwo.setNextReviewAt(NOW.toLocalDate().plusDays(1).atTime(2, 0));
        services.wordRepository().insert(dueAtTwo);
        selectTab("dashboardTab");
        click("refreshDashboardButton");
        // With the default rollover at 4 am, 2 am tonight is still today.
        assertEquals("1", text("dueReviewsLabel"));
        assertEquals(String.valueOf(1 + NEW_WORDS_PER_DAY), text("dueTodayLabel"));

        selectTab("settingsTab");
        assertEquals(4, Fx.call(() -> this.<Integer>comboBox("dayRolloverHourSelector").getValue()));
        this.<Integer>select("dayRolloverHourSelector", hour -> hour == 0);

        assertEquals(0, services.reviewScheduler().options().dayRolloverHour());
        assertEquals("0", services.settingsService().get(SettingsService.DAY_ROLLOVER_HOUR_KEY).orElseThrow());
        selectTab("dashboardTab");
        assertEquals("0", text("dueReviewsLabel"), "due tomorrow when the day starts at midnight");
        assertEquals(String.valueOf(NEW_WORDS_PER_DAY), text("dueTodayLabel"));
        selectTab("decksTab");
        assertEquals(String.valueOf(NEW_WORDS_PER_DAY), cell("deckTable", STARTER_DECK, 2));
        selectTab("wordListTab");
        type("wordSearchField", "obfuscate");
        Fx.waitUntil("the search shows the word", () -> rowCount("wordTable") == 1);
        assertEquals("Learning", cell("wordTable", "obfuscate", 5), "not due today");

        selectTab("settingsTab");
        this.<Integer>select("dayRolloverHourSelector", hour -> hour == 3);
        selectTab("dashboardTab");
        assertEquals("1", text("dueReviewsLabel"), "due today again when the day starts at 3 am");
    }

    @Test
    void theDefaultNewWordsPerDayLimitsEveryDeckWithoutALimitOfItsOwn() {
        selectTab("settingsTab");
        assertEquals(NEW_WORDS_PER_DAY, spinnerValue("defaultNewCardsPerDaySpinner"));
        typeIntoSpinner("defaultNewCardsPerDaySpinner", "7");

        selectTab("dashboardTab");
        assertEquals("7", text("newAvailableTodayLabel"));
        assertEquals("7", text("dueTodayLabel"));
        selectTab("reviewTab");
        assertEquals(7, spinnerValue("newCardsPerDaySpinner"), "the Review tab shows the deck's limit");

        // A limit chosen on the Review tab is the deck's own; the default no longer changes it.
        typeIntoSpinner("newCardsPerDaySpinner", "12");
        selectTab("settingsTab");
        assertTrue(text("newCardsPerDayHintLabel").endsWith(STARTER_DECK + " has its own limit: 12."),
            text("newCardsPerDayHintLabel"));
        typeIntoSpinner("defaultNewCardsPerDaySpinner", "5");
        selectTab("dashboardTab");
        assertEquals("12", text("newAvailableTodayLabel"));
        selectTab("reviewTab");
        assertEquals(12, spinnerValue("newCardsPerDaySpinner"));
    }

    @Test
    void theOfflineSwitchOnTheSettingsTabIsTheHeadersSwitch() {
        selectTab("settingsTab");
        click("settingsOfflineModeToggle");

        assertTrue(services.settingsService().isOfflineMode());
        assertTrue(isSelected("offlineModeToggle"));
        assertTrue(headerSubtitle().endsWith("| Offline mode"), headerSubtitle());
        assertTrue(text("aiStatusLabel").startsWith("Offline mode is on"), text("aiStatusLabel"));

        click("offlineModeToggle");

        assertFalse(services.settingsService().isOfflineMode());
        assertFalse(isSelected("settingsOfflineModeToggle"));
        assertFalse(headerSubtitle().contains("Offline mode"), headerSubtitle());
    }

    @Test
    void theSettingsAreKeptAcrossARestart() {
        selectTab("settingsTab");
        Fx.run(() -> find("desiredRetentionSlider", Slider.class).setValue(0.85));
        this.<Integer>select("dayRolloverHourSelector", hour -> hour == 6);
        typeIntoSpinner("defaultNewCardsPerDaySpinner", "10");
        click("settingsOfflineModeToggle");

        restartApp();

        assertEquals(0.85, services.reviewScheduler().options().desiredRetention());
        assertEquals(6, services.reviewScheduler().options().dayRolloverHour());
        selectTab("settingsTab");
        assertEquals("85%", text("desiredRetentionLabel"));
        assertEquals(0.85, Fx.call(() -> find("desiredRetentionSlider", Slider.class).getValue()), 1e-9);
        assertEquals(6, Fx.call(() -> this.<Integer>comboBox("dayRolloverHourSelector").getValue()));
        assertEquals(10, spinnerValue("defaultNewCardsPerDaySpinner"));
        assertTrue(isSelected("settingsOfflineModeToggle"));
        assertTrue(isSelected("offlineModeToggle"));
        selectTab("dashboardTab");
        assertEquals("10", text("newAvailableTodayLabel"));
    }

    @Test
    void editGoalsOnTheSettingsTabOpensTheGoalsForm() {
        selectTab("settingsTab");
        assertEquals("Every deck: 20 reviews and 5 new words per day; review sessions of 20 cards.",
            text("goalsSummaryLabel"));

        dialogs.submitForm(form -> ((Spinner<?>) form.lookup("#reviewGoalSpinner")).getEditor().setText("30"));
        click("settingsEditGoalsButton");

        assertEquals("Edit goals", dialogs.last(ScriptedDialogs.Kind.FORM).title());
        assertEquals("Every deck: 30 reviews and 5 new words per day; review sessions of 20 cards.",
            text("goalsSummaryLabel"));
        selectTab("dashboardTab");
        assertEquals("0 / 30", text("reviewedTodayLabel"));
    }

    @Test
    void aSettingThatCannotBeSavedIsReportedAndTheControlGoesBack() throws Exception {
        selectTab("settingsTab");
        renameSettingsTable("settings", "settings_unwritable");
        try {
            this.<Integer>select("dayRolloverHourSelector", hour -> hour == 0);
        } finally {
            renameSettingsTable("settings_unwritable", "settings");
        }

        assertEquals("Day rollover not saved", dialogs.takeError().title());
        assertEquals(4, Fx.call(() -> this.<Integer>comboBox("dayRolloverHourSelector").getValue()));
        assertEquals(4, services.reviewScheduler().options().dayRolloverHour());
    }

    @Test
    void theAddImportTabOnlyAddsAndImportsWhileTheSettingsTabConfigures() {
        List<String> addImport = idsOnTab("addImportTab");
        List<String> settings = idsOnTab("settingsTab");
        for (String id : List.of("ecdictPathField", "saveEcdictButton", "aiBaseUrlField", "saveAiButton",
            "testAiButton", "clearAiCacheButton", "settingsOfflineModeToggle", "openDataFolderButton",
            "openLogFolderButton")) {
            assertFalse(addImport.contains(id), id + " is on Add / Import");
            assertTrue(settings.contains(id), id + " is not on Settings");
        }
        assertTrue(addImport.containsAll(List.of("addEnglishField", "lookupField", "importCsvButton")), addImport.toString());
        assertFalse(idsOnTab("statisticsTab").contains("openDataFolderButton"));
        assertTrue(isDisabled("languageSelector"), "a placeholder until the app is translated");

        selectTab("settingsTab");
        snapshot("settings-tab");
        Fx.run(() -> ((ScrollPane) tab("settingsTab").getContent()).setVvalue(1));
        snapshot("settings-tab-end");
    }

    private void renameSettingsTable(String from, String to) throws SQLException {
        try (Connection connection = services.databaseManager().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + from + " RENAME TO " + to);
        }
    }

    private static int days(String ratingButtonText) {
        Matcher matcher = DAYS.matcher(ratingButtonText);
        assertTrue(matcher.find(), "no interval in days: " + ratingButtonText);
        return Integer.parseInt(matcher.group(1));
    }

    private boolean isSelected(String id) {
        return Fx.call(() -> find(id, CheckBox.class).isSelected());
    }

    private int spinnerValue(String id) {
        return Fx.call(() -> (Integer) find(id, Spinner.class).getValue());
    }

    /** Types into the spinner's field and presses Enter. */
    private void typeIntoSpinner(String id, String text) {
        Fx.run(() -> {
            Spinner<?> spinner = find(id, Spinner.class);
            spinner.getEditor().setText(text);
            spinner.getEditor().fireEvent(new ActionEvent());
        });
    }
}
