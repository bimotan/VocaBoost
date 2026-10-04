package com.vocabtrainer.ui.dashboard;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.Exam;
import com.vocabtrainer.service.ExamPlanService;
import com.vocabtrainer.service.ExamSettings;
import com.vocabtrainer.ui.DataChange;
import com.vocabtrainer.ui.ViewContext;
import com.vocabtrainer.ui.Widgets;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.util.StringConverter;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.OptionalInt;

import static com.vocabtrainer.util.Messages.tr;

/**
 * Edits the exam date in a modal form: the exam of every deck or the current deck's own, its name
 * and its date (empty for none). Saving brings the reviews scheduled on or after the exam forward
 * into the last days before it. The Dashboard and the Settings tab open it.
 */
public final class ExamDialog {
    private final ViewContext context;
    private final ExamPlanService planService;

    private ExamDialog(ViewContext context, ExamPlanService planService) {
        this.context = context;
        this.planService = planService;
    }

    /**
     * Shows the form for the current deck; once the exam is saved, publishes
     * {@link DataChange#REVIEW_SETTINGS} (and {@link DataChange#WORDS} when reviews were brought
     * forward). Returns how many reviews were brought forward, empty when the form was cancelled or
     * the exam not saved, which is reported.
     */
    public static OptionalInt open(ViewContext context, ExamPlanService planService) {
        OptionalInt moved = new ExamDialog(context, planService).edit(context.decks().current());
        if (moved.isPresent()) {
            boolean reviewsMoved = moved.getAsInt() > 0;
            context.errors().guard(tr("exam.refreshFailed"), () -> {
                if (reviewsMoved) {
                    context.changes().publish(DataChange.REVIEW_SETTINGS, DataChange.WORDS);
                } else {
                    context.changes().publish(DataChange.REVIEW_SETTINGS);
                }
            });
        }
        return moved;
    }

    /**
     * Shows the form for {@code deck} and saves it on OK; how many reviews were brought forward when
     * it was saved, empty when it was cancelled or not saved.
     */
    private OptionalInt edit(Deck deck) {
        ExamSettings settings = planService.settings();
        boolean ownExam = settings.deckExam(deck.getId()).isPresent();

        ToggleGroup scope = new ToggleGroup();
        RadioButton everyDeck = new RadioButton(tr("exam.form.everyDeck"));
        everyDeck.setId("examScopeEveryDeck");
        everyDeck.setToggleGroup(scope);
        RadioButton thisDeck = new RadioButton(tr("form.onlyDeck", deck.getName()));
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
        datePicker.setPromptText(tr("exam.form.datePrompt"));
        datePicker.setEditable(true);
        show(settings.examFor(deck.getId()), nameField, datePicker);
        // Switching between every deck's exam and the deck's own shows the exam of that choice.
        scope.selectedToggleProperty().addListener((observable, oldToggle, newToggle) ->
            show(newToggle == thisDeck ? settings.examFor(deck.getId()) : settings.defaultExam(), nameField, datePicker));

        GridPane form = new GridPane();
        form.setId("examForm");
        form.setHgap(10);
        form.setVgap(10);
        form.add(Widgets.formLabel(tr("exam.form.for"), everyDeck), 0, 0);
        form.add(new HBox(16, everyDeck, thisDeck), 1, 0);
        form.add(Widgets.formLabel(tr("exam.form.name"), nameField), 0, 1);
        form.add(nameField, 1, 1);
        form.add(Widgets.formLabel(tr("exam.form.date"), datePicker), 0, 2);
        form.add(datePicker, 1, 2);
        form.add(hint(tr("exam.form.hint", deck.getName())), 1, 3);

        if (!context.dialogs().showForm(tr("exam.form.title"), form)) {
            return OptionalInt.empty();
        }
        try {
            LocalDate date = date(datePicker);
            Exam exam = date == null ? null : new Exam(nameField.getText(), date);
            return OptionalInt.of(planService.saveExam(deck.getId(), thisDeck.isSelected(), exam));
        } catch (IllegalArgumentException e) {
            context.errors().showError(tr("exam.notSaved"), e.getMessage());
            return OptionalInt.empty();
        } catch (RuntimeException e) {
            context.errors().reportFailure(tr("exam.notSaved"), e);
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
            throw new IllegalArgumentException(tr("exam.form.badDate"));
        }
    }

    private static Label hint(String text) {
        Label label = Widgets.hint(text);
        // A fixed width lets the grid give the wrapped lines their height.
        label.setPrefWidth(380);
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
