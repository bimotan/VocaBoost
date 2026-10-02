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
        TextField englishField = new TextField(word.getEnglish());
        TextField chineseField = new TextField(word.getChinese());
        TextField posField = new TextField(word.getPartOfSpeech() == null ? "" : word.getPartOfSpeech());
        TextField tagsField = new TextField(word.getTags() == null ? "" : word.getTags());
        TextArea exampleArea = new TextArea(word.getExampleSentence() == null ? "" : word.getExampleSentence());
        TextArea noteArea = new TextArea(word.getNote() == null ? "" : word.getNote());
        exampleArea.setPrefRowCount(3);
        noteArea.setPrefRowCount(3);

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(10);
        form.add(new Label("English"), 0, 0);
        form.add(englishField, 1, 0);
        form.add(new Label("Chinese"), 0, 1);
        form.add(chineseField, 1, 1);
        form.add(new Label("POS"), 0, 2);
        form.add(posField, 1, 2);
        form.add(new Label("Tags"), 0, 3);
        form.add(tagsField, 1, 3);
        form.add(new Label("Example"), 0, 4);
        form.add(exampleArea, 1, 4);
        form.add(new Label("Notes"), 0, 5);
        form.add(noteArea, 1, 5);

        if (!context.dialogs().showForm("Edit word", form)) {
            return false;
        }
        try {
            ValidatedWord validated = validationService.validate(
                englishField.getText(),
                chineseField.getText(),
                word.getPhonetic(),
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
}
