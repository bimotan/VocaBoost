package com.vocabtrainer.ui.dashboard;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.Exam;
import com.vocabtrainer.service.ExamPlanService;
import com.vocabtrainer.service.ExamSettings;
import com.vocabtrainer.ui.ViewContext;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.util.StringConverter;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Edits the exam date in a modal form: the exam of every deck or the current deck's own, its name
 * and its date (empty for none). Saving brings the reviews scheduled on or after the exam forward
 * into the last days before it.
 */
final class ExamDialog {
    private final ViewContext context;
    private final ExamPlanService planService;

    ExamDialog(ViewContext context, ExamPlanService planService) {
        this.context = context;
        this.planService = planService;
    }

    /**
     * Shows the form for {@code deck} and saves it on OK; how many reviews were brought forward when
     * it was saved, empty when it was cancelled or not saved.
     */
    OptionalInt edit(Deck deck) {
        ExamSettings settings = planService.settings();
        boolean ownExam = settings.deckExam(deck.getId()).isPresent();

        ToggleGroup scope = new ToggleGroup();
        RadioButton everyDeck = new RadioButton("Every deck");
        everyDeck.setId("examScopeEveryDeck");
        everyDeck.setToggleGroup(scope);
        RadioButton thisDeck = new RadioButton("Only " + deck.getName());
        thisDeck.setId("examScopeThisDeck");
        thisDeck.setToggleGroup(scope);
        (ownExam ? thisDeck : everyDeck).setSelected(true);

        TextField nameField = new TextField();
        nameField.setId("examNameField");
        nameField.setPromptText(Exam.DEFAULT_NAME);
        nameField.setPrefColumnCount(16);
        DatePicker datePicker = new DatePicker();
        datePicker.setId("examDatePicker");
        datePicker.setConverter(new IsoDates());
        datePicker.setPromptText("yyyy-mm-dd");
        datePicker.setEditable(true);
        show(settings.examFor(deck.getId()), nameField, datePicker);
        // Switching between every deck's exam and the deck's own shows the exam of that choice.
        scope.selectedToggleProperty().addListener((observable, oldToggle, newToggle) ->
            show(newToggle == thisDeck ? settings.examFor(deck.getId()) : settings.defaultExam(), nameField, datePicker));

        GridPane form = new GridPane();
        form.setId("examForm");
        form.setHgap(10);
        form.setVgap(10);
        form.add(new Label("Exam for"), 0, 0);
        form.add(new HBox(16, everyDeck, thisDeck), 1, 0);
        form.add(new Label("Exam"), 0, 1);
        form.add(nameField, 1, 1);
        form.add(new Label("Date"), 0, 2);
        form.add(datePicker, 1, 2);
        form.add(hint("Reviews that would fall on or after the exam day come in the last week before it instead."
            + " Leave the date empty for no exam; for " + deck.getName() + " only, an empty date means it has"
            + " every deck's exam."), 1, 3);

        if (!context.dialogs().showForm("Exam date", form)) {
            return OptionalInt.empty();
        }
        try {
            LocalDate date = date(datePicker);
            Exam exam = date == null ? null : new Exam(nameField.getText(), date);
            return OptionalInt.of(planService.saveExam(deck.getId(), thisDeck.isSelected(), exam));
        } catch (IllegalArgumentException e) {
            context.errors().showError("Exam date not saved", e.getMessage());
            return OptionalInt.empty();
        } catch (RuntimeException e) {
            context.errors().reportFailure("Exam date not saved", e);
            return OptionalInt.empty();
        }
    }

    private static void show(Optional<Exam> exam, TextField nameField, DatePicker datePicker) {
        nameField.setText(exam.map(Exam::name).orElse(Exam.DEFAULT_NAME));
        datePicker.setValue(exam.map(Exam::date).orElse(null));
        datePicker.getEditor().setText(exam.map(found -> found.date().toString()).orElse(""));
    }

    /** The date typed or picked, which OK takes even if it was not committed yet; null when empty. */
    private static LocalDate date(DatePicker datePicker) {
        String text = datePicker.getEditor().getText() == null ? "" : datePicker.getEditor().getText().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Enter the exam date as year-month-day, for example 2026-11-16.");
        }
    }

    private static Label hint(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        // A fixed width lets the grid give the wrapped lines their height.
        label.setPrefWidth(380);
        label.setMinHeight(Region.USE_PREF_SIZE);
        label.setStyle("-fx-text-fill: #6b7280; -fx-font-size: 12px;");
        return label;
    }

    /** Dates as ISO dates (2026-11-16) whatever the system's locale, so typing one is predictable. */
    private static final class IsoDates extends StringConverter<LocalDate> {
        @Override
        public String toString(LocalDate date) {
            return date == null ? "" : date.toString();
        }

        @Override
        public LocalDate fromString(String text) {
            if (text == null || text.isBlank()) {
                return null;
            }
            try {
                return LocalDate.parse(text.trim());
            } catch (DateTimeParseException e) {
                // The picker keeps its value; OK reports the text.
                return null;
            }
        }
    }
}
