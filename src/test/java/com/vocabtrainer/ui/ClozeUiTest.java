package com.vocabtrainer.ui;

import com.vocabtrainer.domain.ReviewLog;
import com.vocabtrainer.domain.ReviewMode;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cloze / 例句填空: the example with the word blanked out and the meaning as a hint; an inflected
 * answer fills the blank, cards without a usable example are skipped and counted, and the review
 * log says CLOZE (review finding G1).
 */
@Tag("ui")
class ClozeUiTest extends MainWindowUiTest {
    @Test
    void theFormInTheSentenceFillsTheBlankAndTheLogSaysCloze() throws SQLException {
        WordCard abate = starterWord("abate");
        abate.setExampleSentence("The winds abated overnight.");
        services.wordRepository().update(abate);
        selectTab("reviewTab");
        selectMode(ReviewMode.CLOZE);
        assertEquals("The winds _____ overnight.", text("reviewWordLabel"));
        assertEquals("Hint: 减弱; 减少 · verb", text("reviewHintLabel"));
        assertEquals("Type the missing word", Fx.call(() -> find("answerField", TextField.class)
            .getPromptText()));
        assertTrue(text("reviewMetaLabel").startsWith("Cloze | New"), text("reviewMetaLabel"));
        snapshot("question");

        type("answerField", "abated");
        pressEnter("answerField");
        waitForBackgroundTasks();

        String result = text("reviewResultArea");
        assertTrue(result.startsWith("Correct answer: abate (in the sentence: abated)" + System.lineSeparator()
            + "Your answer: abated" + System.lineSeparator() + "Answer similarity: 100%"), result);
        assertEquals("The winds abated overnight.", Fx.call(() -> find("reviewDetailsExample", TextFlow.class)
            .getChildren().stream().map(node -> ((Text) node).getText()).reduce("", String::concat)));
        assertEquals("rateGoodButton", focusOwnerId());
        snapshot("answered");
        pressKey(null, KeyCode.DIGIT3);

        List<ReviewLog> logs = services.reviewLogRepository().findByWord(abate.getId());
        assertEquals(1, logs.size());
        assertEquals(ReviewMode.CLOZE, logs.get(0).getDirection());
        assertEquals("abated", logs.get(0).getUserAnswer());
        assertEquals("abate", logs.get(0).getCorrectAnswer());
        assertEquals(ReviewRating.GOOD, logs.get(0).getEffectiveRating());
        assertTrue(text("sessionProgressLabel").startsWith("Session 1/20 | Accuracy 100%"), text("sessionProgressLabel"));
    }

    @Test
    void cardsWithoutAUsableExampleAreSkippedWithAVisibleCount() throws SQLException {
        List<WordCard> firstNew = services.wordRepository()
            .findNewCards(currentDeck().getId(), clock.now().plusDays(1), 3);
        firstNew.get(0).setExampleSentence("");
        firstNew.get(1).setExampleSentence("This sentence does not have the word.");
        firstNew.forEach(word -> {
            try {
                services.wordRepository().update(word);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
        selectTab("reviewTab");

        selectMode(ReviewMode.CLOZE);

        assertEquals("Session 0/20 | Accuracy 0% | XP 0 | 2 cards without examples skipped", text("sessionProgressLabel"));
        assertTrue(text("reviewWordLabel").contains("_____"), text("reviewWordLabel"));
        assertTrue(text("reviewHintLabel").startsWith("Hint: " + firstNew.get(2).getChinese()), text("reviewHintLabel"));
        snapshot("skipped");

        restartApp();
        selectTab("reviewTab");
        assertEquals(ReviewMode.CLOZE, Fx.call(() -> this.<ReviewMode>comboBox("reviewModeSelector").getValue()),
            "the next launch starts in Cloze mode");
        assertTrue(text("sessionProgressLabel").endsWith("| 2 cards without examples skipped"),
            text("sessionProgressLabel"));
    }

    private WordCard starterWord(String english) throws SQLException {
        return services.wordRepository().findByEnglish(currentDeck().getId(), english).orElseThrow();
    }

    private void selectMode(ReviewMode mode) {
        this.<ReviewMode>select("reviewModeSelector", mode::equals);
    }
}
