package com.vocabtrainer.ui;

import com.vocabtrainer.app.AppServices;
import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.ValidatedWord;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.WordRepository;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputControl;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Word List's edit dialog checks and saves while it is open: a mistake or a failed save keeps
 * the dialog open with what was typed, and nothing is shown or saved that was not saved.
 */
@Tag("ui")
class WordEditUiTest extends MainWindowUiTest {
    private volatile boolean failTextUpdates;

    @Override
    AppServices.Builder configure(AppServices.Builder builder) {
        return builder.wordRepository(FailingWordRepository::new);
    }

    /** Fails to save a word's text while {@link #failTextUpdates} is set, like a locked or full disk. */
    private final class FailingWordRepository extends WordRepository {
        FailingWordRepository(DatabaseManager databaseManager) {
            super(databaseManager);
        }

        @Override
        public boolean updateText(long id, ValidatedWord text) throws SQLException {
            if (failTextUpdates) {
                throw new SQLException("[SQLITE_FULL] database or disk is full");
            }
            return super.updateText(id, text);
        }
    }

    @Test
    void invalidInputKeepsTheDialogOpenWithWhatWasTyped() throws Exception {
        WordCard abate = word("abate");
        selectWordInList("abate");

        dialogs.submitForm(form -> {
            field(form, "editChineseField").setText("");
            field(form, "editPhoneticField").setText("/new/");
            field(form, "editTagsField").setText("edited");
        }).submitForm(form -> {
            // OK did not close the form: it says why, and the other edits are still there.
            assertEquals("Chinese meaning cannot be empty.", problem(form).getText());
            assertTrue(problem(form).isVisible());
            assertEquals("/new/", field(form, "editPhoneticField").getText());
            assertEquals("edited", field(form, "editTagsField").getText());
            assertEquals("减弱; 减少", word("abate").getChinese(), "nothing is saved yet");
            field(form, "editChineseField").setText("减轻");
        });
        click("editWordButton");

        WordCard saved = word("abate");
        assertEquals("减轻", saved.getChinese());
        assertEquals("/new/", saved.getPhonetic());
        assertEquals("edited", saved.getTags());
        assertEquals(abate.getId(), saved.getId());
        assertEquals("减轻", cell("wordTable", "abate", 1));
        assertEquals(1, dialogs.shown(ScriptedDialogs.Kind.FORM).size(), "one dialog, opened once");
    }

    @Test
    void aFailedSaveKeepsTheEditsInTheDialogAndShowsOnlyWhatIsSaved() throws Exception {
        selectWordInList("abate");
        failTextUpdates = true;

        dialogs.submitForm(form -> {
            field(form, "editChineseField").setText("减轻");
            field(form, "editTagsField").setText("edited");
        }).submitForm(form -> {
            assertTrue(problem(form).getText().startsWith("Could not save: [SQLITE_FULL] database or disk is full"),
                problem(form).getText());
            assertEquals("减轻", field(form, "editChineseField").getText(), "the edit is still in the form");
            // The table still shows the saved word, not the edit that failed.
            assertEquals("减弱; 减少", cell("wordTable", "abate", 1));
            failTextUpdates = false;
        });
        click("editWordButton");

        assertEquals("减轻", word("abate").getChinese(), "OK again saved it");
        assertEquals("edited", word("abate").getTags());
        assertEquals("减轻", cell("wordTable", "abate", 1));
    }

    @Test
    void cancellingAfterAFailedSaveChangesNothing() throws Exception {
        selectWordInList("abate");
        failTextUpdates = true;

        dialogs.submitForm(form -> field(form, "editChineseField").setText("减轻")).cancelForm();
        click("editWordButton");

        assertEquals("减弱; 减少", word("abate").getChinese());
        assertEquals("减弱; 减少", cell("wordTable", "abate", 1));
        assertEquals("减弱; 减少", Fx.call(() -> this.<WordCard>table("wordTable").getSelectionModel()
            .getSelectedItem().getChinese()), "the row's word is not changed in memory either");
    }

    @Test
    void anEnglishWordAnotherWordHasIsExplainedInTheDialog() throws Exception {
        selectWordInList("abate");

        dialogs.submitForm(form -> field(form, "editEnglishField").setText("ACUMEN"))
            .submitForm(form -> {
                assertEquals("Another word in this deck is already \"acumen\": change the English word, or Cancel.",
                    problem(form).getText());
                field(form, "editEnglishField").setText("abate");
            });
        click("editWordButton");

        assertTrue(services.wordRepository().findByEnglish(currentDeck().getId(), "abate").isPresent());
        assertEquals(STARTER_WORDS, services.wordRepository().countAll(currentDeck().getId()));
    }

    @Test
    void savingAnEditKeepsTheScheduleTheWordHasNowEvenIfTheListIsOlder() throws Exception {
        selectWordInList("abate");
        // The word is reviewed after the list was read (the list row still says it is new).
        WordCard reviewed = word("abate");
        LocalDateTime now = clock.now().truncatedTo(ChronoUnit.SECONDS);
        reviewed.setState(CardState.REVIEW);
        reviewed.setStability(12.5);
        reviewed.setRepetitions(4);
        reviewed.setLastReviewedAt(now);
        reviewed.setNextReviewAt(now.plusDays(12));
        services.wordRepository().update(reviewed);

        dialogs.submitForm(form -> field(form, "editNoteArea").setText("edited note"));
        click("editWordButton");

        WordCard saved = word("abate");
        assertEquals("edited note", saved.getNote());
        assertEquals(CardState.REVIEW, saved.getState());
        assertEquals(12.5, saved.getStability());
        assertEquals(4, saved.getRepetitions());
        assertEquals(now.plusDays(12), saved.getNextReviewAt());
        assertFalse(dialogs.wasShown(ScriptedDialogs.Kind.ERROR));
    }

    /** The word as saved; read on the FX thread too, from inside a form. */
    private WordCard word(String english) {
        try {
            return services.wordRepository().findByEnglish(currentDeck().getId(), english).orElseThrow();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void selectWordInList(String english) {
        selectTab("wordListTab");
        Fx.run(() -> {
            var table = this.<WordCard>table("wordTable");
            table.getSelectionModel().select(table.getItems().stream()
                .filter(item -> item.getEnglish().equals(english))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No row " + english)));
        });
    }

    private static TextInputControl field(Node form, String id) {
        return (TextInputControl) form.lookup("#" + id);
    }

    private static Label problem(Node form) {
        return (Label) form.lookup("#editWordProblemLabel");
    }
}
