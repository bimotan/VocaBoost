package com.vocabtrainer.ui;

import com.vocabtrainer.domain.Deck;
import javafx.scene.Node;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Spinner;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Goals, XP and the streak on the Dashboard: importing earns nothing, a word counts as new on its
 * first review, the Dashboard and the Statistics tab agree, and the goals are edited with "Edit
 * goals" and kept (review findings E8, E10 and G5).
 */
class GoalsUiTest extends MainWindowUiTest {
    @Test
    void importingTwoThousandWordsEarnsNoXpAndNoNewWords() throws Exception {
        Path csv = tempDir.resolve("gre-2000.csv");
        List<String> lines = new ArrayList<>(List.of("english,chinese"));
        for (int i = 0; i < 2000; i++) {
            lines.add("word" + (char) ('a' + i / 676) + (char) ('a' + i / 26 % 26) + (char) ('a' + i % 26) + ",释义");
        }
        Files.write(csv, lines, StandardCharsets.UTF_8);

        selectTab("addImportTab");
        type("importPathField", csv.toString());
        click("importCsvButton");
        waitForBackgroundTasks();
        assertTrue(text("importStatusLabel").contains("Imported 2000"), text("importStatusLabel"));
        assertFalse(text("importStatusLabel").contains("Unlocked"), "no badges for importing");

        selectTab("dashboardTab");
        assertEquals(String.valueOf(STARTER_WORDS + 2000), text("totalWordsLabel"));
        assertEquals("0 / 5", text("newWordsTodayLabel"));
        assertEquals("0", text("xpLabel"));
        assertEquals("None yet", text("badgesLabel"));
    }

    @Test
    void aWordIsNewOnItsFirstReviewAndTheDashboardAgreesWithTheStatistics() throws Exception {
        review(true, "rateGoodButton");

        selectTab("dashboardTab");
        assertEquals("1 / 5", text("newWordsTodayLabel"), "the first review of a new word");
        assertEquals("1 / 20", text("reviewedTodayLabel"));
        assertEquals("100%", text("accuracyTodayLabel"));
        assertEquals("1 days", text("streakLabel"));
        selectTab("statisticsTab");
        List<ChartPoint> reviews = chartPoints("reviewCountChart");
        assertEquals(1.0, reviews.get(reviews.size() - 1).y(), "today's bar is the dashboard's count");
        List<ChartPoint> accuracy = chartPoints("accuracyChart");
        assertEquals(1.0, accuracy.get(accuracy.size() - 1).y());
    }

    @Test
    void editedGoalsAreKeptAndADeckCanHaveItsOwn() throws Exception {
        selectTab("dashboardTab");
        assertEquals("0 / 20", text("reviewedTodayLabel"));
        assertEquals("Default goals of every deck", text("goalScopeLabel"));

        dialogs.submitForm(form -> {
            assertTrue(field(form, "goalScopeEveryDeck", RadioButton.class).isSelected());
            assertEquals(20, spinner(form, "reviewGoalSpinner").getValue());
            setSpinner(form, "reviewGoalSpinner", "30");
            setSpinner(form, "newWordGoalSpinner", "8");
            setSpinner(form, "sessionGoalSpinner", "35");
        });
        click("editGoalsButton");

        assertEquals("Edit goals", dialogs.last(ScriptedDialogs.Kind.FORM).title());
        assertEquals("0 / 30", text("reviewedTodayLabel"));
        assertEquals("0 / 8", text("newWordsTodayLabel"));
        selectTab("reviewTab");
        assertEquals("Custom", Fx.call(() -> this.<String>comboBox("sessionSizeSelector").getValue()));
        assertEquals("35", text("customSessionSizeField"));
        assertTrue(text("sessionProgressLabel").startsWith("Session 0/35"), text("sessionProgressLabel"));

        dialogs.answerText("TOEFL");
        click("newDeckButton");
        selectTab("dashboardTab");
        assertEquals("0 / 30", text("reviewedTodayLabel"), "a new deck has the default goals");
        dialogs.submitForm(form -> {
            field(form, "goalScopeThisDeck", RadioButton.class).setSelected(true);
            setSpinner(form, "reviewGoalSpinner", "10");
            setSpinner(form, "newWordGoalSpinner", "2");
        });
        click("editGoalsButton");
        assertEquals("0 / 10", text("reviewedTodayLabel"));
        assertEquals("This deck's own goals", text("goalScopeLabel"));

        restartApp();
        Deck reopened = currentDeck();
        assertEquals("TOEFL", reopened.getName());
        selectTab("dashboardTab");
        assertEquals("0 / 10", text("reviewedTodayLabel"));
        assertEquals("0 / 2", text("newWordsTodayLabel"));
        selectDeck("deckSelector", STARTER_DECK);
        assertEquals("0 / 30", text("reviewedTodayLabel"));
        assertEquals("0 / 8", text("newWordsTodayLabel"));
        assertEquals("Default goals of every deck", text("goalScopeLabel"));
        selectTab("reviewTab");
        assertTrue(text("sessionProgressLabel").startsWith("Session 0/35"), text("sessionProgressLabel"));

        // The form shows the saved goals; choosing "Every deck" for TOEFL drops its own goals.
        selectDeck("deckSelector", "TOEFL");
        selectTab("dashboardTab");
        dialogs.submitForm(form -> {
            assertTrue(field(form, "goalScopeThisDeck", RadioButton.class).isSelected());
            assertEquals(10, spinner(form, "reviewGoalSpinner").getValue());
            field(form, "goalScopeEveryDeck", RadioButton.class).setSelected(true);
            assertEquals(30, spinner(form, "reviewGoalSpinner").getValue(), "the default goals are shown");
        });
        click("editGoalsButton");
        assertEquals("0 / 30", text("reviewedTodayLabel"));
        assertEquals("Default goals of every deck", text("goalScopeLabel"));
    }

    @Test
    void aGoalThatIsNotANumberIsNotSaved() {
        selectTab("dashboardTab");
        dialogs.submitForm(form -> setSpinner(form, "reviewGoalSpinner", ""));
        click("editGoalsButton");

        ScriptedDialogs.Shown error = dialogs.takeError();
        assertEquals("Goals not saved", error.title());
        assertEquals("Reviews per day must be a whole number.", error.content());
        assertEquals("0 / 20", text("reviewedTodayLabel"));
    }

    @Test
    void goalsThatCannotBeReadAreReportedAndNoFormOpens() throws Exception {
        selectTab("dashboardTab");
        try (Connection connection = services.databaseManager().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE settings RENAME TO settings_unreadable");
        }

        click("editGoalsButton");

        ScriptedDialogs.Shown error = dialogs.takeError();
        assertEquals("Goals not saved", error.title());
        assertTrue(dialogs.shown().stream().noneMatch(shown -> shown.kind() == ScriptedDialogs.Kind.FORM));
        try (Connection connection = services.databaseManager().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE settings_unreadable RENAME TO settings");
        }
    }

    private static <T extends Node> T field(Node form, String id, Class<T> type) {
        Node node = form.lookup("#" + id);
        if (!type.isInstance(node)) {
            throw new AssertionError("No " + type.getSimpleName() + " #" + id + " in the form: " + node);
        }
        return type.cast(node);
    }

    @SuppressWarnings("unchecked")
    private static Spinner<Integer> spinner(Node form, String id) {
        return field(form, id, Spinner.class);
    }

    /** Types into the spinner's field without committing it, as a user who then presses OK. */
    private static void setSpinner(Node form, String id, String text) {
        spinner(form, id).getEditor().setText(text);
    }
}
