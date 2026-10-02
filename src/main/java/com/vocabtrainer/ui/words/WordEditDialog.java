package com.vocabtrainer.ui.words;

import com.vocabtrainer.domain.ValidatedWord;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.service.WordValidationService;
import com.vocabtrainer.ui.UiErrors;
import com.vocabtrainer.ui.ViewContext;
import com.vocabtrainer.ui.Widgets;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;

import java.sql.SQLException;
import java.util.Optional;

/**
 * Edits one word's text fields in a modal form; the review schedule is left alone. OK checks the
 * input and saves it while the form is still open: a mistake, an English word another word of the
 * deck has, or a save that fails is explained in the form, which stays open with everything typed,
 * to be corrected or cancelled. Only the text columns are written, so an edit never puts back an
 * older schedule, and the word the Word List shows is not changed: the list reads the saved word
 * again once the form closed.
 */
final class WordEditDialog {
    private final ViewContext context;
    private final WordRepository wordRepository;
    private final WordValidationService validationService;

    WordEditDialog(ViewContext context, WordRepository wordRepository, WordValidationService validationService) {
        this.context = context;
        this.wordRepository = wordRepository;
        this.validationService = validationService;
    }

    /** Shows the form for {@code word} and saves it on OK; true when the word was saved. */
    boolean edit(WordCard word) {
        TextField englishField = field("editEnglishField", word.getEnglish());
        TextField chineseField = field("editChineseField", word.getChinese());
        TextField phoneticField = field("editPhoneticField", word.getPhonetic());
        TextField posField = field("editPosField", word.getPartOfSpeech());
        TextField tagsField = field("editTagsField", word.getTags());
        TextArea exampleArea = area("editExampleArea", word.getExampleSentence());
        TextArea noteArea = area("editNoteArea", word.getNote());
        phoneticField.setPromptText("e.g. /əˈbeɪt/");
        Label problemLabel = new Label();
        problemLabel.setId("editWordProblemLabel");
        problemLabel.getStyleClass().add("form-error");
        problemLabel.setWrapText(true);
        problemLabel.setMinHeight(Region.USE_PREF_SIZE);
        // As wide as the fields, so a long message wraps instead of widening the dialog.
        problemLabel.prefWidthProperty().bind(englishField.widthProperty());
        showProblem(problemLabel, "");

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(10);
        form.add(Widgets.formLabel("_English", englishField), 0, 0);
        form.add(englishField, 1, 0);
        form.add(Widgets.formLabel("_Chinese", chineseField), 0, 1);
        form.add(chineseField, 1, 1);
        form.add(Widgets.formLabel("_Phonetic", phoneticField), 0, 2);
        form.add(phoneticField, 1, 2);
        form.add(Widgets.formLabel("P_OS", posField), 0, 3);
        form.add(posField, 1, 3);
        form.add(Widgets.formLabel("_Tags", tagsField), 0, 4);
        form.add(tagsField, 1, 4);
        form.add(Widgets.formLabel("E_xample", exampleArea), 0, 5);
        form.add(exampleArea, 1, 5);
        form.add(Widgets.formLabel("_Notes", noteArea), 0, 6);
        form.add(noteArea, 1, 6);
        form.add(problemLabel, 1, 7);

        return context.dialogs().showForm("Edit word", form, () -> {
            Optional<String> problem = save(word, new FormInput(englishField.getText(), chineseField.getText(),
                phoneticField.getText(), posField.getText(), exampleArea.getText(), noteArea.getText(),
                tagsField.getText()));
            showProblem(problemLabel, problem.orElse(""));
            return problem.isEmpty();
        });
    }

    /** What the form holds when OK is pressed. */
    private record FormInput(String english, String chinese, String phonetic, String partOfSpeech, String example,
                             String note, String tags) {
    }

    /** Checks and saves {@code input} as {@code word}'s text; empty when saved, otherwise what went wrong. */
    private Optional<String> save(WordCard word, FormInput input) {
        ValidatedWord validated;
        try {
            validated = validationService.validate(input.english(), input.chinese(), input.phonetic(),
                input.partOfSpeech(), input.example(), input.note(), input.tags());
        } catch (IllegalArgumentException e) {
            return Optional.of(e.getMessage());
        }
        try {
            Optional<WordCard> duplicate = wordRepository.findByEnglish(word.getDeckId(), validated.english());
            if (duplicate.isPresent() && duplicate.get().getId() != word.getId()) {
                return Optional.of("Another word in this deck is already \"" + duplicate.get().getEnglish()
                    + "\": change the English word, or Cancel.");
            }
            if (!wordRepository.updateText(word.getId(), validated)) {
                return Optional.of("This word is no longer in the database, so nothing was saved. Cancel to close.");
            }
            return Optional.empty();
        } catch (SQLException | RuntimeException e) {
            context.errors().logFailure("Save word failed", e);
            return Optional.of("Could not save: " + UiErrors.rootMessage(e)
                + " Your changes are still here: press OK to try again, or Cancel.");
        }
    }

    private static void showProblem(Label label, String problem) {
        label.setText(problem);
        label.setVisible(!problem.isEmpty());
        label.setManaged(!problem.isEmpty());
    }

    private static TextField field(String id, String value) {
        TextField field = new TextField(value == null ? "" : value);
        field.setId(id);
        return field;
    }

    private static TextArea area(String id, String value) {
        TextArea area = new TextArea(value == null ? "" : value);
        area.setId(id);
        area.setPrefRowCount(3);
        area.setWrapText(true);
        return area;
    }
}
