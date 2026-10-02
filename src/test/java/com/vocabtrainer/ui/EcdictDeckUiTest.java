package com.vocabtrainer.ui;

import com.vocabtrainer.app.AppServices;
import com.vocabtrainer.repository.EcdictRepository;
import com.vocabtrainer.service.ecdict.EcdictFixtures;
import com.vocabtrainer.service.ecdict.EcdictImportService;
import com.vocabtrainer.service.ecdict.EcdictTagDeckService;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Create deck from ECDICT tag" on the Decks tab (review finding D3's suggestion): it needs an
 * imported ECDICT, builds the deck in the background and makes it the current deck, and fills it
 * with the next words when built again.
 */
class EcdictDeckUiTest extends MainWindowUiTest {
    /** Generated entries "wa", "wb", ... all tagged gre, the first most common. */
    private static final int TAGGED = 30;

    @Override
    AppServices.Builder configure(AppServices.Builder builder) {
        if (!testName().equals("withoutAnImportedDictionaryItSaysWhereToImportOne")) {
            try {
                Path csv = EcdictFixtures.writeGenerated(tempDir.resolve("ecdict.csv"), TAGGED, "词义");
                try (EcdictRepository ecdict = new EcdictRepository(tempDir.resolve("ecdict.db"))) {
                    new EcdictImportService(ecdict).importCsv(csv, progress -> { }, () -> false);
                }
            } catch (Exception e) {
                throw new IllegalStateException("Cannot prepare the ECDICT dictionary", e);
            }
        }
        return builder;
    }

    @Test
    void withoutAnImportedDictionaryItSaysWhereToImportOne() {
        selectTab("decksTab");
        click("ecdictDeckButton");

        ScriptedDialogs.Shown info = dialogs.last(ScriptedDialogs.Kind.INFO);
        assertTrue(info.content().contains("Import ecdict.csv in the ECDICT box of the Add / Import tab"),
            info.content());
        assertFalse(dialogs.wasShown(ScriptedDialogs.Kind.FORM));
        assertEquals(STARTER_DECK, currentDeck().getName());
    }

    @Test
    void buildsADeckOfTheMostCommonTaggedWordsAndFillsItWithTheNextOnes() {
        selectTab("decksTab");
        dialogs.submitForm(form -> {
            assertEquals(EcdictTagDeckService.Tag.GRE, tagSelector(form).getValue());
            assertEquals("GRE (ECDICT)", deckName(form).getEditor().getText());
            assertTrue(field(form, "ecdictOrderFrequency", RadioButton.class).isSelected());
            field(form, "ecdictLimitField", TextField.class).setText("25");
        });
        click("ecdictDeckButton");
        waitForBackgroundTasks();

        assertEquals("Create deck from ECDICT tag", dialogs.last(ScriptedDialogs.Kind.FORM).title());
        assertEquals("Added 25 GRE words to GRE (ECDICT) (new deck).", text("ecdictDeckStatusLabel"));
        assertFalse(isVisible("ecdictDeckProgressBar"));
        assertFalse(isDisabled("ecdictDeckButton"));
        assertEquals("GRE (ECDICT)", currentDeck().getName(), "the new deck is the current deck");
        assertEquals("25", cell("deckTable", "GRE (ECDICT)", 1));
        snapshot("built");
        selectTab("dashboardTab");
        assertEquals("25", text("totalWordsLabel"));
        assertEquals("0", text("xpLabel"), "adding words earns no XP");

        // The form lists the decks; building into the current one adds the words it does not have yet.
        selectTab("decksTab");
        dialogs.submitForm(form -> {
            assertTrue(deckName(form).getItems().contains("GRE (ECDICT)"));
            assertEquals("", field(form, "ecdictLimitField", TextField.class).getText());
        });
        click("ecdictDeckButton");
        waitForBackgroundTasks();
        assertEquals("Added 5 GRE words to GRE (ECDICT). 25 already in the deck.", text("ecdictDeckStatusLabel"));
        assertEquals(String.valueOf(TAGGED), cell("deckTable", "GRE (ECDICT)", 1));
    }

    @Test
    void aTagWithoutWordsCreatesNoDeckAndANameIsNeeded() {
        selectTab("decksTab");
        dialogs.submitForm(form -> {
            tagSelector(form).setValue(EcdictTagDeckService.Tag.TOEFL);
            assertEquals("TOEFL (ECDICT)", deckName(form).getEditor().getText(), "the name follows the tag");
        });
        click("ecdictDeckButton");
        waitForBackgroundTasks();
        assertEquals("No TOEFL word could be added, so no deck was created. ECDICT tags no word with toefl.",
            text("ecdictDeckStatusLabel"));
        assertEquals(STARTER_DECK, currentDeck().getName());
        assertEquals(1, rowCount("deckTable"));

        dialogs.submitForm(form -> deckName(form).getEditor().setText("  "));
        click("ecdictDeckButton");
        ScriptedDialogs.Shown error = dialogs.takeError();
        assertEquals("Deck not created", error.title());
        assertEquals("Enter the name of the deck to create or fill.", error.content());
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<EcdictTagDeckService.Tag> tagSelector(Node form) {
        return field(form, "ecdictTagSelector", ComboBox.class);
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<String> deckName(Node form) {
        return field(form, "ecdictDeckNameField", ComboBox.class);
    }

    private static <T extends Node> T field(Node form, String id, Class<T> type) {
        Node node = form.lookup("#" + id);
        if (!type.isInstance(node)) {
            throw new AssertionError("No " + type.getSimpleName() + " #" + id + " in the form: " + node);
        }
        return type.cast(node);
    }
}
