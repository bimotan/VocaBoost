package com.vocabtrainer.ui;

import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.ReviewLog;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;
import javafx.collections.ListChangeListener;
import javafx.scene.control.TableView;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mistakes on the Review tab and in the Word List can be taken back (review findings G3, A12). */
@Tag("ui")
class UndoAndSuspendUiTest extends MainWindowUiTest {
    @Test
    void undoTakesBackTheLastRatingAndShowsTheCardWithItsAnswer() throws SQLException {
        selectTab("reviewTab");
        assertTrue(isDisabled("undoButton"), "nothing to undo yet");
        WordCard word = review(true, "rateAgainButton");
        assertNotEquals(word.getEnglish(), text("reviewWordLabel"));
        assertEquals(1, logs().size());
        assertFalse(isDisabled("undoButton"));

        click("undoButton");

        assertEquals(word.getEnglish(), text("reviewWordLabel"));
        assertEquals(correctAnswer(word), text("answerField"));
        assertTrue(isDisabled("answerField"));
        assertFalse(isDisabled("ratingButtons"));
        assertTrue(text("reviewResultArea").startsWith("Undid the Again rating of \"" + word.getEnglish() + "\": XP -"),
            text("reviewResultArea"));
        assertEquals("rateGoodButton", focusOwnerId(), "Space confirms the suggested rating again");
        assertTrue(logs().isEmpty());
        assertEquals(CardState.NEW, stored(word).getState());
        assertTrue(isDisabled("undoButton"));
        snapshot("undone");

        click("rateGoodButton");

        List<ReviewLog> logs = logs();
        assertEquals(1, logs.size());
        assertEquals(ReviewRating.GOOD, logs.get(0).getRating());
        selectTab("dashboardTab");
        assertEquals("1 / 20", text("reviewedTodayLabel"));
    }

    @Test
    void ctrlZUndoesOnTheReviewTabButNotWhileTypingOrOnAnotherTab() throws SQLException {
        WordCard first = review(true, "rateGoodButton");
        WordCard second = review(true, "rateGoodButton");
        assertEquals("answerField", focusOwnerId());
        assertEquals("", text("answerField"));

        pressShortcut(null, KeyCode.Z);

        assertEquals(second.getEnglish(), text("reviewWordLabel"));
        assertEquals(1, logs().size());
        assertEquals(first.getId(), logs().get(0).getWordId());

        selectTab("dashboardTab");
        pressShortcut(null, KeyCode.Z);
        assertEquals(1, logs().size(), "Ctrl+Z does nothing while another tab is shown");

        selectTab("reviewTab");
        click("rateGoodButton");
        type("answerField", "正在输入");
        pressShortcut("answerField", KeyCode.Z);
        assertEquals(2, logs().size(), "Ctrl+Z in a field with text undoes the typing, not the rating");
        assertEquals("正在输入", text("answerField"));

        type("answerField", "");
        pressShortcut("answerField", KeyCode.Z);
        pressShortcut("answerField", KeyCode.Z);
        assertTrue(logs().isEmpty(), "each Ctrl+Z undoes one rating");
        assertEquals(first.getEnglish(), text("reviewWordLabel"));
        assertTrue(isDisabled("undoButton"));
    }

    @Test
    void aDeckSwitchLeavesNothingToUndo() throws SQLException {
        review(true, "rateGoodButton");
        assertFalse(isDisabled("undoButton"));
        dialogs.answerText("TOEFL");
        click("newDeckButton");

        assertTrue(isDisabled("undoButton"));

        selectDeck("deckSelector", STARTER_DECK);
        assertTrue(isDisabled("undoButton"));
        assertEquals(1, logs().size());
    }

    @Test
    void alreadyKnownSkipsLearningANewWord() throws SQLException {
        selectTab("reviewTab");
        WordCard word = questionWord();
        assertFalse(isDisabled("knownButton"));

        click("knownButton");

        assertNotEquals(word.getEnglish(), text("reviewWordLabel"));
        WordCard known = stored(word);
        assertEquals(CardState.REVIEW, known.getState());
        assertTrue(text("reviewResultArea").startsWith("Marked \"" + word.getEnglish() + "\" as already known"),
            text("reviewResultArea"));
        selectTab("dashboardTab");
        assertEquals("0 / 20", text("reviewedTodayLabel"), "marking a word known is no review");
        assertEquals("0", text("xpLabel"));
        assertEquals("0 / 5", text("newWordsTodayLabel"));

        selectTab("reviewTab");
        click("undoButton");
        assertEquals(word.getEnglish(), text("reviewWordLabel"));
        assertEquals(CardState.NEW, stored(word).getState());
    }

    @Test
    void aWordSuspendedOnTheReviewTabIsListedAsSuspended() throws SQLException {
        selectTab("reviewTab");
        WordCard word = questionWord();

        click("suspendCardButton");

        assertNotEquals(word.getEnglish(), text("reviewWordLabel"));
        assertTrue(stored(word).isSuspended());
        assertTrue(text("reviewResultArea").startsWith("Suspended \"" + word.getEnglish() + "\""),
            text("reviewResultArea"));
        selectTab("dashboardTab");
        assertEquals(STARTER_WORDS + " (1 suspended)", text("totalWordsLabel"));
        selectTab("wordListTab");
        assertEquals(STARTER_WORDS, rowCount("wordTable"), "suspended words stay in the Word List");
        assertEquals("Suspended", cell("wordTable", word.getEnglish(), 5));
        Fx.run(() -> this.<String>comboBox("wordStatusFilter").getSelectionModel().select("Suspended"));
        assertEquals(List.of(word.getEnglish()), wordListEnglish());
        snapshot("suspended-filter");
    }

    @Test
    void theWordListSuspendsAndUnsuspendsTheSelectedWords() throws SQLException {
        selectTab("wordListTab");
        assertTrue(isDisabled("suspendWordsButton"));
        assertTrue(isDisabled("unsuspendWordsButton"));
        selectRows("abate", "lucid");
        assertFalse(isDisabled("suspendWordsButton"));
        assertTrue(isDisabled("unsuspendWordsButton"));

        click("suspendWordsButton");

        long deckId = currentDeck().getId();
        assertTrue(services.wordRepository().findByEnglish(deckId, "abate").orElseThrow().isSuspended());
        assertTrue(services.wordRepository().findByEnglish(deckId, "lucid").orElseThrow().isSuspended());
        assertEquals("Suspended", cell("wordTable", "abate", 5));
        assertEquals("Suspended", cell("wordTable", "lucid", 5));
        assertEquals(List.of("abate", "lucid"), selectedEnglish(), "the selection stays");
        assertTrue(isDisabled("suspendWordsButton"));
        assertFalse(isDisabled("unsuspendWordsButton"));
        selectTab("dashboardTab");
        assertEquals(STARTER_WORDS + " (2 suspended)", text("totalWordsLabel"));
        assertEquals(String.valueOf(NEW_WORDS_PER_DAY), text("dueTodayLabel"));
        selectTab("reviewTab");
        assertNotEquals("abate", text("reviewWordLabel"));

        selectTab("wordListTab");
        selectRows("lucid");
        click("unsuspendWordsButton");

        assertFalse(services.wordRepository().findByEnglish(deckId, "lucid").orElseThrow().isSuspended());
        assertTrue(services.wordRepository().findByEnglish(deckId, "abate").orElseThrow().isSuspended());
        assertEquals("Due", cell("wordTable", "lucid", 5));
    }

    @Test
    void suspendingEveryWordReselectsThemAllAtOnce() throws SQLException {
        selectTab("wordListTab");
        Fx.run(() -> table("wordTable").getSelectionModel().selectAll());
        int[] changes = {0};
        Fx.run(() -> this.<WordCard>table("wordTable").getSelectionModel().getSelectedItems()
            .addListener((ListChangeListener<WordCard>) change -> changes[0]++));

        click("suspendWordsButton");

        assertEquals(STARTER_WORDS, services.wordRepository().countSuspended(currentDeck().getId()));
        assertEquals(STARTER_WORDS, selectedEnglish().size(), "the selection stays");
        // One row at a time would be one change per word: seconds with ten thousand selected words.
        assertTrue(changes[0] <= 3, changes[0] + " selection changes");
    }

    @Test
    void deletingAWordOffersToSuspendItInstead() throws SQLException {
        WordCard reviewed = review(true, "rateGoodButton");
        selectTab("wordListTab");
        selectRows(reviewed.getEnglish());
        dialogs.chooseButton("Suspend instead");

        click("deleteWordButton");

        ScriptedDialogs.Shown dialog = dialogs.last(ScriptedDialogs.Kind.CHOOSE);
        assertEquals("Delete word", dialog.title());
        assertEquals("Delete \"" + reviewed.getEnglish() + "\"?", dialog.header());
        assertEquals("Delete | Suspend instead | Cancel", dialog.value());
        assertTrue(dialog.content().startsWith("Its review history (1 review) will be deleted with it"), dialog.content());
        assertTrue(dialog.content().contains("only restoring a JSON backup made before brings it back"), dialog.content());
        assertTrue(dialog.content().contains("Suspend instead to stop reviewing it and keep the history"), dialog.content());
        assertTrue(stored(reviewed).isSuspended());
        assertEquals(1, logs().size(), "suspending keeps the history");
        assertEquals("Suspended", cell("wordTable", reviewed.getEnglish(), 5));

        // A suspended word can only be deleted or kept.
        dialogs.chooseButton("Delete");
        click("deleteWordButton");

        assertEquals("Delete | Cancel", dialogs.last(ScriptedDialogs.Kind.CHOOSE).value());
        assertTrue(services.wordRepository().findById(reviewed.getId()).isEmpty());
        assertTrue(logs().isEmpty());
        assertEquals(STARTER_WORDS - 1, rowCount("wordTable"));
    }

    @Test
    void severalWordsCanBeDeletedTogether() throws SQLException {
        selectTab("wordListTab");
        selectRows("abate", "lucid");
        dialogs.chooseButton("Cancel");
        click("deleteWordButton");
        assertEquals("Delete 2 words?", dialogs.last(ScriptedDialogs.Kind.CHOOSE).header());
        assertTrue(dialogs.last(ScriptedDialogs.Kind.CHOOSE).content().startsWith("They have no review history yet."));
        assertEquals(STARTER_WORDS, rowCount("wordTable"));

        dialogs.chooseButton("Delete");
        click("deleteWordButton");

        assertEquals(STARTER_WORDS - 2, rowCount("wordTable"));
        assertTrue(services.wordRepository().findByEnglish(currentDeck().getId(), "abate").isEmpty());
    }

    private WordCard stored(WordCard word) throws SQLException {
        return services.wordRepository().findById(word.getId()).orElseThrow();
    }

    private List<ReviewLog> logs() throws SQLException {
        return services.reviewLogRepository().findByDeck(currentDeck().getId());
    }

    /** Selects the rows of these words, as Ctrl+click does, the last one last. */
    private void selectRows(String... english) {
        Fx.run(() -> {
            TableView<WordCard> table = table("wordTable");
            table.getSelectionModel().clearSelection();
            for (String each : english) {
                WordCard row = table.getItems().stream().filter(word -> word.getEnglish().equals(each)).findFirst()
                    .orElseThrow(() -> new AssertionError("No row " + each));
                table.getSelectionModel().select(row);
            }
        });
    }

    private List<String> selectedEnglish() {
        return Fx.call(() -> this.<WordCard>table("wordTable").getSelectionModel().getSelectedItems().stream()
            .map(WordCard::getEnglish).sorted().toList());
    }

    private List<String> wordListEnglish() {
        return Fx.call(() -> this.<WordCard>table("wordTable").getItems().stream().map(WordCard::getEnglish).toList());
    }
}
