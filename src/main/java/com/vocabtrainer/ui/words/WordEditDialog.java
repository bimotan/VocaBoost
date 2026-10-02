package com.vocabtrainer.ui.words;

import com.vocabtrainer.domain.ValidatedWord;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.service.WordValidationService;
import com.vocabtrainer.ui.ViewContext;
import com.vocabtrainer.ui.WordFields;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;

import java.sql.SQLException;
import java.util.Optional;

/** Edits one word's text fields in a modal form; the review schedule is left alone. */
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

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(10);
        form.add(new Label("English"), 0, 0);
        form.add(englishField, 1, 0);
        form.add(new Label("Chinese"), 0, 1);
        form.add(chineseField, 1, 1);
        form.add(new Label("Phonetic"), 0, 2);
        form.add(phoneticField, 1, 2);
        form.add(new Label("POS"), 0, 3);
        form.add(posField, 1, 3);
        form.add(new Label("Tags"), 0, 4);
        form.add(tagsField, 1, 4);
        form.add(new Label("Example"), 0, 5);
        form.add(exampleArea, 1, 5);
        form.add(new Label("Notes"), 0, 6);
        form.add(noteArea, 1, 6);

        if (!context.dialogs().showForm("Edit word", form)) {
            return false;
        }
        try {
            ValidatedWord validated = validationService.validate(
                englishField.getText(),
                chineseField.getText(),
                phoneticField.getText(),
                posField.getText(),
                exampleArea.getText(),
                noteArea.getText(),
                tagsField.getText()
            );
            Optional<WordCard> duplicate = wordRepository.findByEnglish(word.getDeckId(), validated.english());
            if (duplicate.isPresent() && duplicate.get().getId() != word.getId()) {
                context.errors().showError("Save failed", "Another word already uses this English value.");
                return false;
            }
            word.setEnglish(validated.english());
            word.setChinese(validated.chinese());
            WordFields.applyValidatedFields(word, validated);
            wordRepository.save(word);
            return true;
        } catch (SQLException | RuntimeException e) {
            context.errors().reportFailure("Save failed", e);
            return false;
        }
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
