package com.vocabtrainer.ui.importing;

import com.vocabtrainer.domain.Achievement;
import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.DictionaryEntry;
import com.vocabtrainer.domain.GoalUpdate;
import com.vocabtrainer.domain.ValidatedWord;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.domain.WordVerificationResult;
import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.service.AchievementService;
import com.vocabtrainer.service.GoalService;
import com.vocabtrainer.service.WordValidationService;
import com.vocabtrainer.ui.ConfiguredServices;
import com.vocabtrainer.ui.DataChange;
import com.vocabtrainer.ui.DeckContext;
import com.vocabtrainer.ui.Formats;
import com.vocabtrainer.ui.ViewContext;
import com.vocabtrainer.ui.Widgets;
import com.vocabtrainer.ui.WordFields;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.sql.SQLException;
import java.util.List;

/** The manual add form, with a dictionary lookup that fills it in. */
final class AddWordBox {
    private final ViewContext context;
    private final WordRepository wordRepository;
    private final WordValidationService validationService;
    private final GoalService goalService;
    private final AchievementService achievementService;
    private final ConfiguredServices configured;

    private final TextField englishField = textField("addEnglishField", "English");
    private final TextField chineseField = textField("addChineseField", "Chinese meaning");
    private final TextField phoneticField = textField("addPhoneticField", "Phonetic");
    private final TextField posField = textField("addPosField", "Part of speech");
    private final TextField tagsField = textField("addTagsField", "Tags");
    private final TextArea exampleArea = textArea("addExampleArea", "Example sentence", 2);
    private final TextArea noteArea = textArea("addNoteArea", "Notes", 3);
    private final Label statusLabel = new Label();
    private final ComboBox<Deck> addDeckSelector = Widgets.deckComboBox("addDeckSelector", 260);
    private final VBox root;

    AddWordBox(ViewContext context, WordRepository wordRepository, WordValidationService validationService,
               GoalService goalService, AchievementService achievementService, ConfiguredServices configured) {
        this.context = context;
        this.wordRepository = wordRepository;
        this.validationService = validationService;
        this.goalService = goalService;
        this.achievementService = achievementService;
        this.configured = configured;

        statusLabel.setId("addWordStatusLabel");
        statusLabel.setWrapText(true);
        // The target deck follows the current deck; the user can pick another one for a single add.
        DeckContext decks = context.decks();
        decks.activeDecks().addListener((ListChangeListener<Deck>) change -> showDecks());
        decks.onSwitch(deck -> showDecks());
        showDecks();

        Button addButton = new Button("Add word");
        addButton.setId("addWordButton");
        addButton.setOnAction(event -> addWordFromForm());

        GridPane addForm = new GridPane();
        addForm.setHgap(10);
        addForm.setVgap(10);
        addForm.add(new Label("Add to deck"), 0, 0);
        addForm.add(addDeckSelector, 1, 0);
        addForm.add(new Label("English"), 0, 1);
        addForm.add(englishField, 1, 1);
        addForm.add(new Label("Chinese"), 0, 2);
        addForm.add(chineseField, 1, 2);
        addForm.add(new Label("Phonetic"), 0, 3);
        addForm.add(phoneticField, 1, 3);
        addForm.add(new Label("POS"), 0, 4);
        addForm.add(posField, 1, 4);
        addForm.add(new Label("Tags"), 0, 5);
        addForm.add(tagsField, 1, 5);
        addForm.add(new Label("Example"), 0, 6);
        addForm.add(exampleArea, 1, 6);
        addForm.add(new Label("Notes"), 0, 7);
        addForm.add(noteArea, 1, 7);
        addForm.add(addButton, 1, 8);
        addForm.add(statusLabel, 1, 9);

        DictionaryLookupBox lookup = new DictionaryLookupBox(context, validationService, configured, this::fillFrom);
        root = new VBox(24, Widgets.sectionTitle("Manual Add"), addForm, lookup.root());
    }

    Node root() {
        return root;
    }

    private void showDecks() {
        Widgets.showDecks(addDeckSelector, context.decks().activeDecks(), context.decks().currentId());
    }

    private void fillFrom(DictionaryEntry entry) {
        englishField.setText(entry.english());
        chineseField.setText(entry.chinese() == null ? "" : entry.chinese());
        phoneticField.setText(entry.phonetic());
        posField.setText(entry.partOfSpeech());
        exampleArea.setText(entry.example());
        noteArea.setText(dictionaryNote(entry));
        tagsField.setText(WordFields.appendTag("dictionary", entry.source()));
    }

    private void addWordFromForm() {
        try {
            Deck targetDeck = addDeckSelector.getValue();
            if (targetDeck == null) {
                statusLabel.setText("Please select a target deck first.");
                return;
            }
            ValidatedWord validated = validationService.validate(
                englishField.getText(),
                chineseField.getText(),
                phoneticField.getText(),
                posField.getText(),
                exampleArea.getText(),
                noteArea.getText(),
                tagsField.getText()
            );
            if (wordRepository.findByEnglish(targetDeck.getId(), validated.english()).isPresent()) {
                statusLabel.setText("Word already exists in " + targetDeck.getName() + ": "
                    + validated.english() + ". Edit it in Word List.");
                return;
            }
            WordVerificationResult verification = configured.dictionary().verify(validated.english());
            String tags;
            if (!verification.found()) {
                if (!confirmUnverifiedAdd(validated.english(), verification.message())) {
                    statusLabel.setText("Canceled: " + validated.english());
                    return;
                }
                tags = WordFields.appendTag(validated.tags(), "UNVERIFIED");
            } else {
                tags = WordFields.appendTag(WordFields.appendTag(validated.tags(), "VERIFIED"), verification.source());
            }
            ValidatedWord wordToSave = new ValidatedWord(
                validated.english(),
                validated.chinese(),
                validated.phonetic(),
                validated.partOfSpeech(),
                validated.exampleSentence(),
                validated.note(),
                tags
            );
            WordCard word = WordCard.createNew(targetDeck.getId(), wordToSave.english(), wordToSave.chinese());
            WordFields.applyValidatedFields(word, wordToSave);
            wordRepository.save(word);
            // The word is saved: clear the form now so a later failure can't invite a duplicate add.
            englishField.clear();
            chineseField.clear();
            phoneticField.clear();
            posField.clear();
            exampleArea.clear();
            noteArea.clear();
            tagsField.clear();
            String addedText = "Added to " + targetDeck.getName() + ": " + wordToSave.english()
                + (verification.found() ? " | Verified by " + verification.source() : " | Marked UNVERIFIED");
            statusLabel.setText(addedText);
            context.errors().guard("Word added, but updating progress failed", () -> {
                GoalUpdate update = goalService.recordNewWords(targetDeck.getId(), 1);
                List<Achievement> unlocked = achievementService.evaluate(targetDeck.getId(), update.progress(), false,
                    update.dailyGoalCompleted());
                statusLabel.setText(addedText + Formats.unlockedSuffix(unlocked));
                context.changes().publish(DataChange.WORDS);
            });
        } catch (IllegalArgumentException e) {
            statusLabel.setText(e.getMessage());
        } catch (SQLException | RuntimeException e) {
            context.errors().reportFailure("Add failed", e);
        }
    }

    private boolean confirmUnverifiedAdd(String english, String message) {
        return context.dialogs().confirm("词条未找到", "词条未找到：" + english,
            (message == null || message.isBlank() ? "本地和在线词典都未验证该词条。" : message)
                + System.lineSeparator() + "是否强制添加并标记为 UNVERIFIED？");
    }

    private static String dictionaryNote(DictionaryEntry entry) {
        StringBuilder builder = new StringBuilder();
        if (entry.definition() != null && !entry.definition().isBlank()) {
            builder.append("English definition: ").append(entry.definition().trim());
        }
        if (entry.source() != null && !entry.source().isBlank()) {
            if (builder.length() > 0) {
                builder.append(System.lineSeparator());
            }
            builder.append("Dictionary source: ").append(entry.source().trim());
        }
        return builder.toString();
    }

    private static TextField textField(String id, String prompt) {
        TextField field = new TextField();
        field.setId(id);
        field.setPromptText(prompt);
        return field;
    }

    private static TextArea textArea(String id, String prompt, int rows) {
        TextArea area = new TextArea();
        area.setId(id);
        area.setPromptText(prompt);
        area.setPrefRowCount(rows);
        return area;
    }
}
