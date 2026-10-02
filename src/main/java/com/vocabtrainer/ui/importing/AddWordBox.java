package com.vocabtrainer.ui.importing;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.DictionaryEntry;
import com.vocabtrainer.domain.GoalUpdate;
import com.vocabtrainer.domain.LookupOutcome;
import com.vocabtrainer.domain.ValidatedWord;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.domain.WordVerificationResult;
import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.service.AchievementService;
import com.vocabtrainer.service.DictionaryService;
import com.vocabtrainer.service.GoalService;
import com.vocabtrainer.service.WordValidationService;
import com.vocabtrainer.ui.ConfiguredServices;
import com.vocabtrainer.ui.DataChange;
import com.vocabtrainer.ui.DeckContext;
import com.vocabtrainer.ui.Formats;
import com.vocabtrainer.ui.LatestRequest;
import com.vocabtrainer.ui.UiAsync;
import com.vocabtrainer.ui.UiErrors;
import com.vocabtrainer.ui.ViewContext;
import com.vocabtrainer.ui.Widgets;
import com.vocabtrainer.ui.WordFields;
import javafx.collections.ListChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The manual add form, with a dictionary lookup that fills it in. Adding first checks the word with
 * the dictionaries in the background, so a slow or unreachable online dictionary never freezes the
 * window: the Add button stays usable, changing the English word cancels the check, and a check
 * that finishes for a word no longer in the form is dropped. A word the dictionaries do not have is
 * added as UNVERIFIED only after confirmation; when they could not be asked, the user can retry,
 * add it unchecked (tag UNCHECKED) or cancel, and in offline mode add it unchecked or cancel.
 */
final class AddWordBox {
    private static final String UNVERIFIED_TAG = "UNVERIFIED";
    private static final String UNCHECKED_TAG = "UNCHECKED";

    private final ViewContext context;
    private final WordRepository wordRepository;
    private final WordValidationService validationService;
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
    private final ProgressIndicator busyIndicator = LookupMessages.busyIndicator("addWordBusyIndicator");
    private final LatestRequest checks = new LatestRequest();
    private final VBox root;
    private UiAsync.Cancellable runningCheck;
    /** The English word being checked while {@link #runningCheck} runs. */
    private String checkedWord;

    AddWordBox(ViewContext context, WordRepository wordRepository, WordValidationService validationService,
               GoalService goalService, AchievementService achievementService, ConfiguredServices configured) {
        this.context = context;
        this.wordRepository = wordRepository;
        this.validationService = validationService;
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
        englishField.textProperty().addListener((obs, oldText, newText) -> {
            if (runningCheck != null && !sameWord(validationService.normalizeEnglish(newText), checkedWord)) {
                String canceled = checkedWord;
                cancelCheck();
                statusLabel.setText("Canceled adding " + canceled + ": the English word changed.");
            }
        });
        HBox addRow = new HBox(10, addButton, busyIndicator);
        addRow.setAlignment(Pos.CENTER_LEFT);

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
        addForm.add(addRow, 1, 8);
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

    /** Checks the form and the English word, then adds the word (see {@link #decide}). */
    private void addWordFromForm() {
        try {
            Deck targetDeck = addDeckSelector.getValue();
            if (targetDeck == null) {
                statusLabel.setText("Please select a target deck first.");
                return;
            }
            ValidatedWord validated = validateForm();
            if (wordRepository.findByEnglish(targetDeck.getId(), validated.english()).isPresent()) {
                statusLabel.setText("Word already exists in " + targetDeck.getName() + ": "
                    + validated.english() + ". Edit it in Word List.");
                return;
            }
            checkThenAdd(validated.english());
        } catch (IllegalArgumentException e) {
            statusLabel.setText(e.getMessage());
        } catch (SQLException | RuntimeException e) {
            context.errors().reportFailure("Add failed", e);
        }
    }

    /** Asks the dictionaries about {@code english} in the background; a check still running is canceled. */
    private void checkThenAdd(String english) {
        cancelCheck();
        long ticket = checks.next();
        checkedWord = english;
        LookupMessages.setBusy(busyIndicator, true);
        statusLabel.setText("Checking " + english + " in the dictionaries...");
        DictionaryService dictionary = configured.dictionary();
        runningCheck = context.async().start(
            () -> dictionary.verify(english),
            verification -> {
                if (checks.isLatest(ticket)) {
                    checkFinished();
                    decide(english, verification);
                }
            },
            error -> {
                if (checks.isLatest(ticket)) {
                    checkFinished();
                    context.errors().logFailure("Checking " + english + " failed", error);
                    statusLabel.setText("Checking " + english + " failed: " + UiErrors.rootMessage(error));
                }
            });
    }

    private void decide(String english, WordVerificationResult verification) {
        switch (verification.status()) {
            case VERIFIED -> addFromForm(english,
                tags -> WordFields.appendTag(WordFields.appendTag(tags, "VERIFIED"), verification.source()),
                " | Verified by " + verification.source());
            case UNVERIFIED -> {
                if (confirmUnverifiedAdd(english, verification.message())) {
                    addFromForm(english, tags -> WordFields.appendTag(tags, UNVERIFIED_TAG), " | Marked " + UNVERIFIED_TAG);
                } else {
                    statusLabel.setText("Canceled: " + english);
                }
            }
            case UNCHECKED -> {
                // In offline mode asking again cannot help, so there is no Retry.
                boolean offline = verification.outcome() == LookupOutcome.OFFLINE;
                ButtonType retry = new ButtonType("重试", ButtonBar.ButtonData.OTHER);
                ButtonType addUnchecked = new ButtonType("直接添加", ButtonBar.ButtonData.OK_DONE);
                ButtonType cancel = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
                String question = offline
                    ? "不经验证直接添加并标记为 " + UNCHECKED_TAG + "？"
                    : "重试，或不经验证直接添加并标记为 " + UNCHECKED_TAG + "？";
                String content = LookupMessages.headline(verification.outcome()) + System.lineSeparator()
                    + verification.message() + System.lineSeparator() + question;
                Optional<ButtonType> choice = offline
                    ? context.dialogs().choose("无法验证", "无法验证：" + english, content, addUnchecked, cancel)
                    : context.dialogs().choose("无法验证", "无法验证：" + english, content, retry, addUnchecked, cancel);
                if (choice.isPresent() && choice.get() == retry) {
                    checkThenAdd(english);
                } else if (choice.isPresent() && choice.get() == addUnchecked) {
                    addFromForm(english, tags -> WordFields.appendTag(tags, UNCHECKED_TAG), offline
                        ? " | Not checked: offline mode is on"
                        : " | Not checked: the dictionaries could not be asked");
                } else {
                    statusLabel.setText("Canceled: " + english);
                }
            }
        }
    }

    /**
     * Saves what the form holds now, which may have been edited while its English word was checked;
     * a change of the English word itself cancels the check, so it is still {@code checkedEnglish}.
     */
    private void addFromForm(String checkedEnglish, UnaryOperator<String> tagging, String verificationText) {
        try {
            Deck targetDeck = addDeckSelector.getValue();
            if (targetDeck == null) {
                statusLabel.setText("Please select a target deck first.");
                return;
            }
            ValidatedWord validated = validateForm();
            if (!sameWord(validated.english(), checkedEnglish)) {
                statusLabel.setText("Canceled adding " + checkedEnglish + ": the English word changed.");
                return;
            }
            if (wordRepository.findByEnglish(targetDeck.getId(), validated.english()).isPresent()) {
                statusLabel.setText("Word already exists in " + targetDeck.getName() + ": "
                    + validated.english() + ". Edit it in Word List.");
                return;
            }
            ValidatedWord wordToSave = new ValidatedWord(
                validated.english(),
                validated.chinese(),
                validated.phonetic(),
                validated.partOfSpeech(),
                validated.exampleSentence(),
                validated.note(),
                tagging.apply(validated.tags())
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
            String addedText = "Added to " + targetDeck.getName() + ": " + wordToSave.english() + verificationText;
            statusLabel.setText(addedText);
            // Adding earns no XP: the word counts as a new word on the day of its first review.
            context.errors().guard("Word added, but refreshing the views failed",
                () -> context.changes().publish(DataChange.WORDS));
        } catch (IllegalArgumentException e) {
            statusLabel.setText(e.getMessage());
        } catch (SQLException | RuntimeException e) {
            context.errors().reportFailure("Add failed", e);
        }
    }

    private ValidatedWord validateForm() {
        return validationService.validate(
            englishField.getText(),
            chineseField.getText(),
            phoneticField.getText(),
            posField.getText(),
            exampleArea.getText(),
            noteArea.getText(),
            tagsField.getText()
        );
    }

    private void cancelCheck() {
        if (runningCheck != null) {
            runningCheck.cancel();
            checks.invalidate();
            checkFinished();
        }
    }

    private void checkFinished() {
        runningCheck = null;
        checkedWord = null;
        LookupMessages.setBusy(busyIndicator, false);
    }

    private static boolean sameWord(String left, String right) {
        return left != null && right != null && left.toLowerCase(Locale.ROOT).equals(right.toLowerCase(Locale.ROOT));
    }

    private boolean confirmUnverifiedAdd(String english, String message) {
        return context.dialogs().confirm("词条未找到", "词条未找到：" + english,
            (message == null || message.isBlank() ? "本地和在线词典都未验证该词条。" : message)
                + System.lineSeparator() + "是否强制添加并标记为 UNVERIFIED？");
    }

    private static String dictionaryNote(DictionaryEntry entry) {
        StringBuilder builder = new StringBuilder();
        if (entry.note() != null && !entry.note().isBlank()) {
            // Senses the dictionary marks as specialist ("[网络] ..."), kept out of the answer key.
            builder.append(entry.note().trim().replace("\n", System.lineSeparator()));
        }
        if (entry.definition() != null && !entry.definition().isBlank()) {
            if (builder.length() > 0) {
                builder.append(System.lineSeparator());
            }
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
