package com.vocabtrainer.ui.importing;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.DictionaryEntry;
import com.vocabtrainer.domain.GoalUpdate;
import com.vocabtrainer.domain.LookupOutcome;
import com.vocabtrainer.domain.ValidatedWord;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.domain.WordVerificationResult;
import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.service.DictionaryService;
import com.vocabtrainer.service.LocalDictionaryService;
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
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.vocabtrainer.util.Messages.tr;

/**
 * The manual add form, with a dictionary lookup that fills it in. Adding first checks the word with
 * the dictionaries in the background, so a slow or unreachable online dictionary never freezes the
 * window: the Add button stays usable, changing the English word cancels the check, and a check
 * that finishes for a word no longer in the form is dropped. A word the dictionaries do not have is
 * added as UNVERIFIED only after confirmation; when they could not be asked, the user can retry,
 * add it unchecked (tag UNCHECKED) or cancel, and in offline mode add it unchecked or cancel. A word
 * the dictionaries have gets their phonetic when the form leaves it empty.
 *
 * <p>A word another active deck already has is not added without asking: the user can copy that
 * deck's meaning, part of speech, example and phonetic into the form and add it, add it as typed, or
 * cancel. Either way the new card has its own schedule in the target deck (see docs/ARCHITECTURE.md,
 * Word List and Decks).
 */
final class AddWordBox {
    private static final String UNVERIFIED_TAG = "UNVERIFIED";
    private static final String UNCHECKED_TAG = "UNCHECKED";

    private final ViewContext context;
    private final WordRepository wordRepository;
    private final WordValidationService validationService;
    private final ConfiguredServices configured;

    private final TextField englishField = textField("addEnglishField", tr("add.prompt.english"));
    private final TextField chineseField = textField("addChineseField", tr("add.prompt.chinese"));
    private final TextField phoneticField = textField("addPhoneticField", tr("word.phonetic"));
    private final TextField posField = textField("addPosField", tr("word.partOfSpeech"));
    private final TextField tagsField = textField("addTagsField", tr("word.tags"));
    private final TextArea exampleArea = textArea("addExampleArea", tr("add.prompt.example"), 2);
    private final TextArea noteArea = textArea("addNoteArea", tr("add.prompt.notes"), 3);
    private final Label statusLabel = new Label();
    private final ComboBox<Deck> addDeckSelector = Widgets.deckComboBox("addDeckSelector", 260);
    private final ProgressIndicator busyIndicator = LookupMessages.busyIndicator("addWordBusyIndicator");
    private final LatestRequest checks = new LatestRequest();
    private final VBox root;
    private UiAsync.Cancellable runningCheck;
    /** The English word being checked while {@link #runningCheck} runs. */
    private String checkedWord;
    /** The deck whose details the user copied into the form for the word being added, or null. */
    private String copiedFrom;

    AddWordBox(ViewContext context, WordRepository wordRepository, WordValidationService validationService,
               ConfiguredServices configured) {
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

        Button addButton = new Button(tr("add.button"));
        addButton.setId("addWordButton");
        addButton.setOnAction(event -> addWordFromForm());
        englishField.textProperty().addListener((obs, oldText, newText) -> {
            if (runningCheck != null && !sameWord(validationService.normalizeEnglish(newText), checkedWord)) {
                String canceled = checkedWord;
                cancelCheck();
                statusLabel.setText(tr("add.canceledWordChanged", canceled));
            }
        });
        HBox addRow = new HBox(10, addButton, busyIndicator);
        addRow.setAlignment(Pos.CENTER_LEFT);

        GridPane addForm = new GridPane();
        addForm.setHgap(10);
        addForm.setVgap(10);
        // Alt and the underlined letter moves to a field; the header's Deck selector has D.
        addForm.add(Widgets.formLabel(tr("add.label.deck"), addDeckSelector), 0, 0);
        addForm.add(addDeckSelector, 1, 0);
        addForm.add(Widgets.formLabel(tr("add.label.english"), englishField), 0, 1);
        addForm.add(englishField, 1, 1);
        addForm.add(Widgets.formLabel(tr("add.label.chinese"), chineseField), 0, 2);
        addForm.add(chineseField, 1, 2);
        addForm.add(Widgets.formLabel(tr("add.label.phonetic"), phoneticField), 0, 3);
        addForm.add(phoneticField, 1, 3);
        addForm.add(Widgets.formLabel(tr("add.label.pos"), posField), 0, 4);
        addForm.add(posField, 1, 4);
        addForm.add(Widgets.formLabel(tr("add.label.tags"), tagsField), 0, 5);
        addForm.add(tagsField, 1, 5);
        addForm.add(Widgets.formLabel(tr("add.label.example"), exampleArea), 0, 6);
        addForm.add(exampleArea, 1, 6);
        addForm.add(Widgets.formLabel(tr("add.label.notes"), noteArea), 0, 7);
        addForm.add(noteArea, 1, 7);
        addForm.add(addRow, 1, 8);
        addForm.add(statusLabel, 1, 9);

        DictionaryLookupBox lookup = new DictionaryLookupBox(context, validationService, configured, this::fillFrom);
        root = new VBox(24, Widgets.sectionTitle(tr("add.title")), addForm, lookup.root());
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
                statusLabel.setText(tr("add.selectDeck"));
                return;
            }
            String english = validationService.validateEnglishOnly(englishField.getText());
            if (wordRepository.findByEnglish(targetDeck.getId(), english).isPresent()) {
                statusLabel.setText(tr("add.exists", targetDeck.getName(), english));
                return;
            }
            // A meaning left empty can still be copied from another deck; any other mistake is shown first.
            boolean hasMeaning = !validationService.normalizeChinese(chineseField.getText()).isBlank();
            if (hasMeaning) {
                validateForm();
            }
            copiedFrom = null;
            List<WordCard> elsewhere = wordRepository.findInOtherDecks(english, targetDeck.getId());
            if (!elsewhere.isEmpty() && !askAboutOtherDecks(english, targetDeck, elsewhere, hasMeaning)) {
                statusLabel.setText(tr("add.canceled", english));
                return;
            }
            checkThenAdd(validateForm().english());
        } catch (IllegalArgumentException e) {
            statusLabel.setText(e.getMessage());
        } catch (SQLException | RuntimeException e) {
            context.errors().reportFailure(tr("add.failed"), e);
        }
    }

    /**
     * Says which other decks have {@code english} and what they have for it, and offers to copy the
     * first one's meaning, part of speech, example and phonetic into the form. Adding it as typed is
     * offered only when {@code hasMeaning}: the form has a Chinese meaning of its own. False when the
     * user cancels; true to go on adding, with the form as typed or as copied.
     */
    private boolean askAboutOtherDecks(String english, Deck targetDeck, List<WordCard> elsewhere,
                                       boolean hasMeaning) {
        WordCard source = elsewhere.get(0);
        String sourceDeck = deckName(source.getDeckId());
        StringBuilder content = new StringBuilder();
        for (WordCard word : elsewhere) {
            content.append(tr("add.otherDeck.line", deckName(word.getDeckId()), summary(word)))
                .append(System.lineSeparator());
        }
        content.append(System.lineSeparator()).append(tr("add.otherDeck.copyHint", sourceDeck, targetDeck.getName()));
        ButtonType copy = new ButtonType(tr("add.otherDeck.copy"), ButtonBar.ButtonData.OK_DONE);
        ButtonType asTyped = new ButtonType(tr("add.otherDeck.asTyped"), ButtonBar.ButtonData.OTHER);
        ButtonType cancel = new ButtonType(tr("common.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        String decks = elsewhere.stream().map(word -> deckName(word.getDeckId())).distinct()
            .collect(Collectors.joining(tr("format.listSeparator")));
        String title = tr("add.otherDeck.title");
        String header = tr("add.otherDeck.header", english, decks);
        Optional<ButtonType> choice = hasMeaning
            ? context.dialogs().choose(title, header, content.toString(), copy, asTyped, cancel)
            : context.dialogs().choose(title, header, content.toString(), copy, cancel);
        if (choice.isEmpty() || choice.get() == cancel) {
            return false;
        }
        if (choice.get() == copy) {
            chineseField.setText(source.getChinese());
            copyIfPresent(posField, source.getPartOfSpeech());
            copyIfPresent(exampleArea, source.getExampleSentence());
            copyIfPresent(phoneticField, source.getPhonetic());
            copiedFrom = sourceDeck;
        }
        return true;
    }

    /** "减轻; 减少 · verb · /əˈbeɪt/ · The storm began to abate." with the parts the word has. */
    private static String summary(WordCard word) {
        return Stream.of(word.getChinese(), word.getPartOfSpeech(), word.getPhonetic(),
                word.getExampleSentence())
            .filter(part -> part != null && !part.isBlank())
            .map(String::trim)
            .collect(Collectors.joining(" · "));
    }

    /** Puts {@code value} into {@code field}, unless it is blank: the form keeps what the user typed. */
    private static void copyIfPresent(TextInputControl field, String value) {
        if (value != null && !value.isBlank()) {
            field.setText(value);
        }
    }

    private String deckName(long deckId) {
        return context.decks().activeDecks().stream()
            .filter(deck -> deck.getId() == deckId)
            .map(Deck::getName)
            .findFirst()
            .orElse(tr("add.anotherDeck"));
    }

    /** Asks the dictionaries about {@code english} in the background; a check still running is canceled. */
    private void checkThenAdd(String english) {
        cancelCheck();
        long ticket = checks.next();
        checkedWord = english;
        LookupMessages.setBusy(busyIndicator, true);
        statusLabel.setText(tr("add.checking", english));
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
                    context.errors().logFailure(tr("add.check.failed.title", english), error);
                    statusLabel.setText(tr("add.check.failed", english, UiErrors.rootMessage(error)));
                }
            });
    }

    private void decide(String english, WordVerificationResult verification) {
        switch (verification.status()) {
            case VERIFIED -> {
                String filled = "";
                if (phoneticField.getText().isBlank() && !verification.phonetic().isEmpty()) {
                    phoneticField.setText(verification.phonetic());
                    filled = " | " + tr("add.phoneticFilled", verification.phonetic());
                }
                addFromForm(english,
                    tags -> WordFields.appendTag(WordFields.appendTag(tags, "VERIFIED"), verification.source()),
                    " | " + tr("add.verifiedBy", LocalDictionaryService.sourceLabel(verification.source())) + filled);
            }
            case UNVERIFIED -> {
                if (confirmUnverifiedAdd(english, verification.message())) {
                    addFromForm(english, tags -> WordFields.appendTag(tags, UNVERIFIED_TAG),
                        " | " + tr("add.marked", UNVERIFIED_TAG));
                } else {
                    statusLabel.setText(tr("add.canceled", english));
                }
            }
            case UNCHECKED -> {
                // In offline mode asking again cannot help, so there is no Retry.
                boolean offline = verification.outcome() == LookupOutcome.OFFLINE;
                ButtonType retry = new ButtonType(tr("common.retry"), ButtonBar.ButtonData.OTHER);
                ButtonType addUnchecked = new ButtonType(tr("add.unchecked.add"), ButtonBar.ButtonData.OK_DONE);
                ButtonType cancel = new ButtonType(tr("common.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
                String question = offline ? tr("add.unchecked.questionOffline", UNCHECKED_TAG)
                    : tr("add.unchecked.question", UNCHECKED_TAG);
                String content = LookupMessages.headline(verification.outcome()) + System.lineSeparator()
                    + verification.message() + System.lineSeparator() + question;
                String title = tr("add.unchecked.title");
                String header = tr("add.unchecked.header", english);
                Optional<ButtonType> choice = offline
                    ? context.dialogs().choose(title, header, content, addUnchecked, cancel)
                    : context.dialogs().choose(title, header, content, retry, addUnchecked, cancel);
                if (choice.isPresent() && choice.get() == retry) {
                    checkThenAdd(english);
                } else if (choice.isPresent() && choice.get() == addUnchecked) {
                    addFromForm(english, tags -> WordFields.appendTag(tags, UNCHECKED_TAG), " | " + (offline
                        ? tr("add.notChecked.offline") : tr("add.notChecked.unavailable")));
                } else {
                    statusLabel.setText(tr("add.canceled", english));
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
                statusLabel.setText(tr("add.selectDeck"));
                return;
            }
            ValidatedWord validated = validateForm();
            if (!sameWord(validated.english(), checkedEnglish)) {
                statusLabel.setText(tr("add.canceledWordChanged", checkedEnglish));
                return;
            }
            if (wordRepository.findByEnglish(targetDeck.getId(), validated.english()).isPresent()) {
                statusLabel.setText(tr("add.exists", targetDeck.getName(), validated.english()));
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
            WordCard word = WordCard.createNew(targetDeck.getId(), wordToSave.english(), wordToSave.chinese(),
                LocalDateTime.now(context.clock()));
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
            String addedText = tr("add.added", targetDeck.getName(), wordToSave.english()) + verificationText
                + (copiedFrom == null ? "" : " | " + tr("add.copiedFrom", copiedFrom));
            copiedFrom = null;
            statusLabel.setText(addedText);
            // Adding earns no XP: the word counts as a new word on the day of its first review.
            context.errors().guard(tr("add.refreshFailed"),
                () -> context.changes().publish(DataChange.WORDS));
        } catch (IllegalArgumentException e) {
            statusLabel.setText(e.getMessage());
        } catch (SQLException | RuntimeException e) {
            context.errors().reportFailure(tr("add.failed"), e);
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
        return context.dialogs().confirm(tr("add.unverified.title"), tr("add.unverified.header", english),
            (message == null || message.isBlank() ? tr("add.unverified.noMessage") : message)
                + System.lineSeparator() + tr("add.unverified.question", UNVERIFIED_TAG));
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
            builder.append(tr("lookup.result.definition", entry.definition().trim()));
        }
        if (entry.source() != null && !entry.source().isBlank()) {
            if (builder.length() > 0) {
                builder.append(System.lineSeparator());
            }
            builder.append(tr("add.note.source", LocalDictionaryService.sourceLabel(entry.source().trim())));
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
