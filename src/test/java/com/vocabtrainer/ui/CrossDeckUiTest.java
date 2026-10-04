package com.vocabtrainer.ui;

import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.WordCard;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputControl;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Searching every deck in the Word List, and adding a word that another deck already has. */
@Tag("ui")
class CrossDeckUiTest extends MainWindowUiTest {
    private static final String STARTER_ABATE = "减弱; 减少";

    @Test
    void allDecksSearchesEveryActiveDeckAndShowsWhichDeckAWordIsIn() throws Exception {
        long starterDeckId = currentDeck().getId();
        dialogs.answerText("TOEFL");
        click("newDeckButton");
        long toeflId = currentDeck().getId();
        services.wordRepository().insert(WordCard.createNew(toeflId, "abate", "减轻", clock.now()));
        services.wordRepository().insert(WordCard.createNew(toeflId, "zeugma", "轭式修辞", clock.now()));
        selectDeck("deckSelector", STARTER_DECK);
        selectTab("wordListTab");
        assertEquals(STARTER_WORDS, rowCount("wordTable"));
        assertFalse(deckColumnVisible());

        click("wordAllDecksToggle");

        assertEquals(STARTER_WORDS + 2, rowCount("wordTable"));
        assertTrue(deckColumnVisible());
        type("wordSearchField", "abate");
        // A word's rows are in the order of the deck selector (by name).
        assertEquals(List.of("abate " + STARTER_DECK, "abate TOEFL"), rows());
        assertEquals(STARTER_ABATE, chineseOfRow(0));
        assertEquals("减轻", chineseOfRow(1));
        // The details say which deck the selected word is in.
        Fx.run(() -> this.<WordCard>table("wordTable").getSelectionModel().select(1));
        assertEquals("abate   减轻   (TOEFL)", text("wordDetailsTitle"));
        snapshot("all-decks");
        type("wordSearchField", "zeugma");
        assertEquals(List.of("zeugma TOEFL"), rows());

        // The other deck's words can be edited from here; the edit stays in that deck.
        Fx.run(() -> this.<WordCard>table("wordTable").getSelectionModel().select(0));
        dialogs.submitForm(form -> ((TextInputControl) form.lookup("#editChineseField"))
            .setText("轭式搭配"));
        click("editWordButton");
        WordCard zeugma = services.wordRepository().findByEnglish(toeflId, "zeugma").orElseThrow();
        assertEquals("轭式搭配", zeugma.getChinese());
        assertEquals(toeflId, zeugma.getDeckId());
        type("wordSearchField", "轭式搭配");
        assertEquals(List.of("zeugma TOEFL"), rows());

        click("wordAllDecksToggle");
        type("wordSearchField", "");
        assertEquals(STARTER_WORDS, rowCount("wordTable"));
        assertFalse(deckColumnVisible());
        assertEquals(starterDeckId, currentDeck().getId(), "searching all decks does not switch decks");
    }

    @Test
    void deletingFromAllDecksSaysWhichDecksWordGoes() throws Exception {
        long starterDeckId = currentDeck().getId();
        dialogs.answerText("TOEFL");
        click("newDeckButton");
        long toeflId = currentDeck().getId();
        services.wordRepository().insert(WordCard.createNew(toeflId, "abate", "减轻", clock.now()));
        selectDeck("deckSelector", STARTER_DECK);
        selectTab("wordListTab");
        click("wordAllDecksToggle");
        type("wordSearchField", "abate");
        Fx.run(() -> this.<WordCard>table("wordTable").getSelectionModel().selectAll());
        dialogs.chooseButton("Cancel");
        click("deleteWordButton");
        assertEquals("Delete 2 words from 2 decks?", dialogs.last(ScriptedDialogs.Kind.CHOOSE).header());

        // TOEFL's abate, after the starter deck's by the decks' names.
        Fx.run(() -> this.<WordCard>table("wordTable").getSelectionModel().clearAndSelect(1));
        dialogs.chooseButton("Delete");

        click("deleteWordButton");

        assertEquals("Delete \"abate\" from TOEFL?", dialogs.last(ScriptedDialogs.Kind.CHOOSE).header());
        assertTrue(services.wordRepository().findByEnglish(toeflId, "abate").isEmpty());
        assertTrue(services.wordRepository().findByEnglish(starterDeckId, "abate").isPresent());
        assertEquals(List.of("abate " + STARTER_DECK), rows());
    }

    @Test
    void addingAWordAnotherDeckHasOffersToCopyItsDetails() throws Exception {
        WordCard starterAbate = services.wordRepository().findByEnglish(currentDeck().getId(), "abate").orElseThrow();
        dialogs.answerText("TOEFL");
        click("newDeckButton");
        long toeflId = currentDeck().getId();
        selectTab("addImportTab");
        type("addEnglishField", "Abate");
        type("addChineseField", "减轻");
        dialogs.chooseButton("Copy and add");
        click("addWordButton");

        waitForTextStartingWith("addWordStatusLabel", "Added to TOEFL: Abate | Verified by");
        assertTrue(text("addWordStatusLabel").endsWith(" | Details copied from " + STARTER_DECK),
            text("addWordStatusLabel"));
        ScriptedDialogs.Shown question = dialogs.last(ScriptedDialogs.Kind.CHOOSE);
        assertEquals("Word in another deck", question.title());
        assertEquals("Abate is already in " + STARTER_DECK, question.header());
        assertTrue(question.content().startsWith(STARTER_DECK + ": " + STARTER_ABATE + " · verb"), question.content());
        assertTrue(question.content().contains("gets its own review schedule"), question.content());
        assertEquals("Copy and add | Add as typed | Cancel", question.value());

        WordCard copy = services.wordRepository().findByEnglish(toeflId, "abate").orElseThrow();
        assertEquals(STARTER_ABATE, copy.getChinese());
        assertEquals("verb", copy.getPartOfSpeech());
        assertEquals(starterAbate.getExampleSentence(), copy.getExampleSentence());
        assertEquals(CardState.NEW, copy.getState(), "the copy has a schedule of its own");
        assertTrue(copy.getId() != starterAbate.getId());
        assertEquals(STARTER_ABATE, services.wordRepository().findById(starterAbate.getId()).orElseThrow().getChinese());
    }

    @Test
    void aWordTypedWithoutAMeaningCanTakeTheOtherDecksMeaning() throws Exception {
        dialogs.answerText("TOEFL");
        click("newDeckButton");
        long toeflId = currentDeck().getId();
        selectTab("addImportTab");
        type("addEnglishField", "abate");
        dialogs.chooseButton("Copy and add");
        click("addWordButton");

        waitForTextStartingWith("addWordStatusLabel", "Added to TOEFL: abate | Verified by");
        // Adding it as typed would fail without a meaning, so it is not offered.
        assertEquals("Copy and add | Cancel", dialogs.last(ScriptedDialogs.Kind.CHOOSE).value());
        assertEquals(STARTER_ABATE, services.wordRepository().findByEnglish(toeflId, "abate").orElseThrow().getChinese());

        // A word no other deck has still needs a meaning.
        type("addEnglishField", "obfuscate");
        click("addWordButton");
        assertEquals("Chinese meaning cannot be empty.", text("addWordStatusLabel"));
        assertTrue(services.wordRepository().findByEnglish(toeflId, "obfuscate").isEmpty());
    }

    @Test
    void theUserCanAddTheWordAsTypedOrCancel() throws Exception {
        dialogs.answerText("TOEFL");
        click("newDeckButton");
        long toeflId = currentDeck().getId();
        selectTab("addImportTab");
        type("addEnglishField", "abate");
        type("addChineseField", "减轻");
        dialogs.chooseButton("Cancel");
        click("addWordButton");

        assertEquals("Canceled: abate", text("addWordStatusLabel"));
        assertEquals("减轻", text("addChineseField"), "the form is kept");
        assertTrue(services.wordRepository().findByEnglish(toeflId, "abate").isEmpty());

        dialogs.chooseButton("Add as typed");
        click("addWordButton");

        waitForTextStartingWith("addWordStatusLabel", "Added to TOEFL: abate | Verified by");
        assertFalse(text("addWordStatusLabel").contains("copied"), text("addWordStatusLabel"));
        assertEquals("减轻", services.wordRepository().findByEnglish(toeflId, "abate").orElseThrow().getChinese());
    }

    @Test
    void aWordNoOtherDeckHasIsAddedWithoutTheQuestion() {
        selectTab("addImportTab");
        type("addEnglishField", "obfuscate");
        type("addChineseField", "使模糊");
        click("addWordButton");

        waitForTextStartingWith("addWordStatusLabel", "Added to " + STARTER_DECK + ": obfuscate | Verified by");
        assertFalse(dialogs.wasShown(ScriptedDialogs.Kind.CHOOSE));
    }

    private boolean deckColumnVisible() {
        return Fx.call(() -> deckColumn().isVisible());
    }

    private TableColumn<WordCard, ?> deckColumn() {
        TableView<WordCard> table = table("wordTable");
        return table.getColumns().stream()
            .filter(column -> "Deck".equals(column.getText()))
            .findFirst()
            .orElseThrow();
    }

    /** "english deck" of every row, in table order. */
    private List<String> rows() {
        return Fx.call(() -> {
            TableView<WordCard> table = table("wordTable");
            TableColumn<WordCard, ?> deck = deckColumn();
            return table.getItems().stream().map(word -> word.getEnglish() + " " + deck.getCellData(word)).toList();
        });
    }

    private String chineseOfRow(int row) {
        return Fx.call(() -> this.<WordCard>table("wordTable").getItems().get(row).getChinese());
    }
}
