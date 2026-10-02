package com.vocabtrainer.ui;

import com.vocabtrainer.domain.Deck;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("ui")
class DeckUiTest extends MainWindowUiTest {
    @Test
    void aNewDeckBecomesCurrentAndSwitchingBackShowsTheStarterDeckAgain() throws Exception {
        long starterDeckId = currentDeck().getId();
        dialogs.answerText("TOEFL");
        click("newDeckButton");

        ScriptedDialogs.Shown prompt = dialogs.last(ScriptedDialogs.Kind.ASK_TEXT);
        assertEquals("New deck", prompt.title());
        assertEquals("Create a new deck", prompt.header());
        assertEquals("", prompt.value());
        Deck toefl = currentDeck();
        assertEquals("TOEFL", toefl.getName());
        // Decks are listed by name.
        assertEquals(List.of("TOEFL", STARTER_DECK), deckNames("deckSelector"));
        assertEquals("Deck: TOEFL | Dictionary: starter/online fallback | AI: mock", headerSubtitle());
        assertEquals("0", text("totalWordsLabel"));
        assertEquals("0", text("dueTodayLabel"));
        selectTab("wordListTab");
        assertEquals(0, rowCount("wordTable"));
        assertEquals("TOEFL", Fx.call(() -> this.<Deck>comboBox("addDeckSelector").getValue().getName()));
        assertEquals("Review complete", text("reviewWordLabel"));
        selectTab("decksTab");
        assertEquals(List.of("TOEFL", STARTER_DECK), deckRowNames("deckTable"));
        assertEquals(toefl.getId(), services.settingsService().getLastDeckId().orElseThrow());

        selectDeck("deckSelector", STARTER_DECK);

        assertEquals(starterDeckId, currentDeck().getId());
        assertEquals("Deck: " + STARTER_DECK + " | Dictionary: starter/online fallback | AI: mock", headerSubtitle());
        selectTab("dashboardTab");
        assertEquals(String.valueOf(STARTER_WORDS), text("totalWordsLabel"));
        selectTab("wordListTab");
        assertEquals(STARTER_WORDS, rowCount("wordTable"));
        assertEquals(STARTER_DECK, Fx.call(() -> this.<Deck>comboBox("addDeckSelector").getValue().getName()));
        assertEquals(starterDeckId, services.settingsService().getLastDeckId().orElseThrow());

        // The Decks tab switches decks too.
        selectTab("decksTab");
        selectDeckRow("deckTable", "TOEFL");
        click("switchDeckButton");
        assertEquals("TOEFL", currentDeck().getName());
        selectTab("dashboardTab");
        assertEquals("0", text("totalWordsLabel"));
    }

    @Test
    void cancellingTheNewDeckPromptChangesNothing() {
        dialogs.cancelText();
        click("newDeckButton");

        assertEquals(STARTER_DECK, currentDeck().getName());
        assertEquals(List.of(STARTER_DECK), deckNames("deckSelector"));
    }

    @Test
    void aDuplicateDeckNameIsRejectedWithTheReason() {
        dialogs.answerText(STARTER_DECK);
        click("newDeckButton");

        ScriptedDialogs.Shown error = dialogs.takeError();
        assertEquals("Create deck failed", error.title());
        assertEquals("已有同名词库「" + STARTER_DECK + "」，请换一个名称", error.content());
        assertEquals(List.of(STARTER_DECK), deckNames("deckSelector"));
    }

    @Test
    void renamingTheDeckUpdatesTheHeaderSelectorAndDeckTable() throws Exception {
        long deckId = currentDeck().getId();
        dialogs.answerText("GRE 核心");
        click("renameDeckButton");

        ScriptedDialogs.Shown prompt = dialogs.last(ScriptedDialogs.Kind.ASK_TEXT);
        assertEquals("Rename deck", prompt.title());
        assertEquals(STARTER_DECK, prompt.value());
        assertEquals(deckId, currentDeck().getId());
        assertEquals("GRE 核心", currentDeck().getName());
        assertEquals(List.of("GRE 核心"), deckNames("deckSelector"));
        assertTrue(headerSubtitle().startsWith("Deck: GRE 核心 | "), headerSubtitle());
        selectTab("decksTab");
        assertEquals(List.of("GRE 核心"), deckRowNames("deckTable"));
        selectTab("dashboardTab");
        assertEquals(String.valueOf(STARTER_WORDS), text("totalWordsLabel"));
        assertEquals("GRE 核心", services.deckRepository().findById(deckId).orElseThrow().getName());
    }

    @Test
    void archivingADeckSwitchesToTheRemainingDeckAndRestoringBringsItBack() throws Exception {
        dialogs.answerText("Temp");
        click("newDeckButton");
        long tempId = currentDeck().getId();

        dialogs.confirm(true);
        click("archiveDeckButton");

        ScriptedDialogs.Shown confirmation = dialogs.last(ScriptedDialogs.Kind.CONFIRM);
        assertEquals("Archive deck", confirmation.title());
        assertEquals("Archive Temp?", confirmation.header());
        assertEquals(STARTER_DECK, currentDeck().getName());
        assertEquals(List.of(STARTER_DECK), deckNames("deckSelector"));
        assertTrue(headerSubtitle().startsWith("Deck: " + STARTER_DECK + " | "), headerSubtitle());
        selectTab("decksTab");
        assertEquals(1, rowCount("deckTable"));
        assertEquals(1, rowCount("archivedDeckTable"));
        assertEquals(List.of("Temp"), deckRowNames("archivedDeckTable"));
        assertTrue(services.deckRepository().findById(tempId).orElseThrow().isArchived());

        selectTab("decksTab");
        selectDeckRow("archivedDeckTable", "Temp");
        click("restoreDeckButton");

        assertEquals(tempId, currentDeck().getId());
        assertEquals(List.of("Temp", STARTER_DECK), deckNames("deckSelector"));
        assertTrue(headerSubtitle().startsWith("Deck: Temp | "), headerSubtitle());
        assertEquals(2, rowCount("deckTable"));
        assertEquals(0, rowCount("archivedDeckTable"));
        assertFalse(services.deckRepository().findById(tempId).orElseThrow().isArchived());
    }

    @Test
    void restoringWithoutASelectedArchivedDeckAsksForOne() {
        selectTab("decksTab");
        click("restoreDeckButton");

        assertEquals("Please select an archived deck to restore.", dialogs.last(ScriptedDialogs.Kind.INFO).content());
    }

    @Test
    void theLastActiveDeckCannotBeArchived() throws Exception {
        long deckId = currentDeck().getId();
        dialogs.confirm(true);
        click("archiveDeckButton");

        ScriptedDialogs.Shown error = dialogs.takeError();
        assertEquals("Archive deck failed", error.title());
        assertEquals("至少需要保留一个活动词库：请先新建或恢复另一个词库，再归档这个词库", error.content());
        assertEquals(deckId, currentDeck().getId());
        selectTab("decksTab");
        assertEquals(0, rowCount("archivedDeckTable"));
        assertFalse(services.deckRepository().findById(deckId).orElseThrow().isArchived());
    }

    @Test
    void cancellingTheArchiveConfirmationKeepsTheDeck() throws Exception {
        dialogs.answerText("Temp");
        click("newDeckButton");
        long tempId = currentDeck().getId();

        dialogs.confirm(false);
        click("archiveDeckButton");

        assertEquals(tempId, currentDeck().getId());
        selectTab("decksTab");
        assertEquals(0, rowCount("archivedDeckTable"));
        assertFalse(services.deckRepository().findById(tempId).orElseThrow().isArchived());
    }

    /** The Deck column of a deck table, top to bottom. */
    private List<String> deckRowNames(String tableId) {
        return Fx.call(() -> {
            var table = this.<Object>table(tableId);
            var nameColumn = table.getColumns().get(0);
            return table.getItems().stream().map(row -> String.valueOf(nameColumn.getCellData(row))).toList();
        });
    }

    private void selectDeckRow(String tableId, String deckName) {
        int row = deckRowNames(tableId).indexOf(deckName);
        assertTrue(row >= 0, deckName + " is not in #" + tableId);
        Fx.run(() -> table(tableId).getSelectionModel().select(row));
    }

    private List<String> deckNames(String comboBoxId) {
        return Fx.call(() -> this.<Deck>comboBox(comboBoxId).getItems().stream().map(Deck::getName).toList());
    }
}
