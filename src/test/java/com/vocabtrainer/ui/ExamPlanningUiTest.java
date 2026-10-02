package com.vocabtrainer.ui;

import com.vocabtrainer.TestClock;
import com.vocabtrainer.app.AppServices;
import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.WordCard;
import javafx.scene.Node;
import javafx.scene.control.DatePicker;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Planning for the exam (review finding G5): the exam date and its countdown on the Dashboard, the
 * new-word plan and its button, a deck's own exam, and the rating buttons keeping reviews before the
 * exam.
 */
class ExamPlanningUiTest extends MainWindowUiTest {
    private final TestClock clock = new TestClock(LocalDateTime.now().plusSeconds(1));

    @Override
    AppServices.Builder configure(AppServices.Builder builder) {
        return builder.clock(clock);
    }

    @Test
    void theExamDateShowsACountdownAndANewWordPlanThatSetsTheDailyLimit() {
        selectTab("dashboardTab");
        assertEquals("No exam date", text("examCountdownLabel"));
        assertFalse(isVisible("newWordPlanLabel"));
        assertFalse(isVisible("applyNewWordPlanButton"));

        LocalDate exam = today().plusDays(45);
        dialogs.submitForm(form -> {
            assertTrue(field(form, "examScopeEveryDeck", RadioButton.class).isSelected());
            assertEquals("GRE", field(form, "examNameField", TextField.class).getText());
            assertEquals("", datePicker(form).getEditor().getText());
            datePicker(form).getEditor().setText(exam.toString());
        });
        click("editExamButton");

        assertEquals("Exam date", dialogs.last(ScriptedDialogs.Kind.FORM).title());
        assertEquals("GRE in 45 days", text("examCountdownLabel"));
        assertTrue(text("examDateLabel").contains(exam + " · every deck's exam"), text("examDateLabel"));
        assertEquals("To finish 215 new words before the exam you need ~5 new words/day; the limit of 20/day is"
            + " enough.", text("newWordPlanLabel"));
        assertFalse(isVisible("applyNewWordPlanButton"), "the limit is enough");
        assertEquals("Saved.", text("examStatusLabel"));
        snapshot("countdown");

        // Ten days are not enough at 20 a day: the button sets the limit the plan needs.
        dialogs.submitForm(form -> {
            assertEquals(exam.toString(), datePicker(form).getEditor().getText(), "the saved date");
            datePicker(form).getEditor().setText(today().plusDays(10).toString());
        });
        click("editExamButton");
        assertEquals("GRE in 10 days", text("examCountdownLabel"));
        assertEquals("To finish 215 new words before the exam you need ~22 new words/day (now 20).",
            text("newWordPlanLabel"));
        assertEquals("Use 22 new words/day", text("applyNewWordPlanButton"));
        click("applyNewWordPlanButton");

        assertEquals("New words per day set to 22.", text("examStatusLabel"));
        assertFalse(isVisible("applyNewWordPlanButton"));
        assertEquals("22", text("newAvailableTodayLabel"));
        selectTab("reviewTab");
        assertEquals(22, Fx.call(() -> find("newCardsPerDaySpinner", Spinner.class).getValue()),
            "the Review tab follows the new limit");

        restartApp();
        selectTab("dashboardTab");
        assertEquals("GRE in 10 days", text("examCountdownLabel"));
    }

    @Test
    void aDeckCanHaveItsOwnExamAndAnExamThatIsOverSaysSo() {
        selectTab("dashboardTab");
        saveExam(form -> datePicker(form).getEditor().setText(today().plusDays(30).toString()));
        dialogs.answerText("TOEFL");
        click("newDeckButton");
        selectTab("dashboardTab");
        assertEquals("GRE in 30 days", text("examCountdownLabel"), "a new deck has every deck's exam");

        saveExam(form -> {
            field(form, "examScopeThisDeck", RadioButton.class).setSelected(true);
            field(form, "examNameField", TextField.class).setText("TOEFL");
            datePicker(form).getEditor().setText(today().plusDays(12).toString());
        });
        assertEquals("TOEFL in 12 days", text("examCountdownLabel"));
        assertTrue(text("examDateLabel").endsWith("this deck's exam"), text("examDateLabel"));
        selectDeck("deckSelector", STARTER_DECK);
        assertEquals("GRE in 30 days", text("examCountdownLabel"));

        // The form shows the deck's own exam; clearing its date goes back to every deck's.
        selectDeck("deckSelector", "TOEFL");
        saveExam(form -> {
            assertTrue(field(form, "examScopeThisDeck", RadioButton.class).isSelected());
            assertEquals("TOEFL", field(form, "examNameField", TextField.class).getText());
            datePicker(form).getEditor().setText("");
        });
        assertEquals("GRE in 30 days", text("examCountdownLabel"));

        saveExam(form -> datePicker(form).getEditor().setText(today().minusDays(3).toString()));
        assertEquals("GRE was 3 days ago", text("examCountdownLabel"));
        assertTrue(text("examDateLabel").endsWith("Set the next date or clear it."), text("examDateLabel"));
        assertFalse(isVisible("newWordPlanLabel"), "no plan for an exam that is over");
        saveExam(form -> datePicker(form).getEditor().setText(today().toString()));
        assertEquals("GRE is today", text("examCountdownLabel"));

        dialogs.submitForm(form -> datePicker(form).getEditor().setText("next week"));
        click("editExamButton");
        ScriptedDialogs.Shown error = dialogs.takeError();
        assertEquals("Exam date not saved", error.title());
        assertEquals("Enter the exam date as year-month-day, for example 2026-11-16.", error.content());
        assertEquals("GRE is today", text("examCountdownLabel"));
    }

    @Test
    void theRatingButtonsKeepTheReviewBeforeTheExam() throws Exception {
        // A mature card, due now, whose next interval would reach far past an exam in 20 days.
        WordCard word = services.wordRepository().findByEnglish(currentDeck().getId(), "abate").orElseThrow();
        word.setState(CardState.REVIEW);
        word.setStability(60);
        word.setDifficulty(5);
        word.setRepetitions(4);
        word.setConsecutiveCorrect(4);
        word.setIntervalDays(60);
        word.setLastReviewedAt(clock.now().minusDays(60));
        word.setNextReviewAt(clock.now().minusHours(1));
        services.wordRepository().save(word);
        LocalDate exam = today().plusDays(20);
        selectTab("dashboardTab");
        saveExam(form -> datePicker(form).getEditor().setText(exam.toString()));
        selectTab("reviewTab");
        click("resetSessionButton");
        assertEquals("abate", text("reviewWordLabel"));

        type("answerField", correctAnswer(word));
        click("submitAnswerButton");
        waitForBackgroundTasks();
        String good = text("rateGoodButton");
        assertTrue(good.endsWith("d (exam)"), good);
        assertTrue(text("rateEasyButton").endsWith("d (exam)"), text("rateEasyButton"));
        snapshot("rating-buttons");
        click("rateGoodButton");

        LocalDate due = services.wordRepository().findById(word.getId()).orElseThrow().getNextReviewAt().toLocalDate();
        assertTrue(due.isBefore(exam) && !due.isBefore(exam.minusDays(7)), "due " + due + ", exam " + exam);
        assertEquals("Good (3) · " + (due.toEpochDay() - today().toEpochDay()) + "d (exam)", good);
    }

    @Test
    void savingAnExamBringsForwardReviewsAlreadyScheduledAfterIt() throws Exception {
        WordCard word = services.wordRepository().findByEnglish(currentDeck().getId(), "lucid").orElseThrow();
        word.setState(CardState.REVIEW);
        word.setStability(90);
        word.setDifficulty(5);
        word.setIntervalDays(90);
        word.setLastReviewedAt(clock.now().minusDays(10));
        word.setNextReviewAt(clock.now().plusDays(80));
        services.wordRepository().save(word);
        selectTab("dashboardTab");

        saveExam(form -> datePicker(form).getEditor().setText(today().plusDays(30).toString()));

        assertEquals("Saved. 1 review due on or after the exam now comes before it.", text("examStatusLabel"));
        LocalDate due = services.wordRepository().findById(word.getId()).orElseThrow().getNextReviewAt().toLocalDate();
        assertTrue(due.isBefore(today().plusDays(30)) && due.isAfter(today().plusDays(22)), due.toString());
    }

    private void saveExam(Consumer<Node> fill) {
        dialogs.submitForm(fill);
        click("editExamButton");
    }

    private LocalDate today() {
        return services.reviewScheduler().studyDay().of(clock.now());
    }

    private static DatePicker datePicker(Node form) {
        return field(form, "examDatePicker", DatePicker.class);
    }

    private static <T extends Node> T field(Node form, String id, Class<T> type) {
        Node node = form.lookup("#" + id);
        if (!type.isInstance(node)) {
            throw new AssertionError("No " + type.getSimpleName() + " #" + id + " in the form: " + node);
        }
        return type.cast(node);
    }
}
