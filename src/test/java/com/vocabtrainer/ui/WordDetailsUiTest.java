package com.vocabtrainer.ui;

import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.ReviewMode;
import com.vocabtrainer.domain.WordCard;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.Region;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The data a word has beyond its meaning (phonetic, part of speech, example, note, tags) is shown
 * once the answer is checked and in the Word List, and no mode gives an answer away before it is
 * submitted (review findings G2 and G1).
 */
@Tag("ui")
class WordDetailsUiTest extends MainWindowUiTest {
    static final String PHONETIC = "/əˈbeɪt/";
    static final String NOTE = "English definition: become less intense. 也作“减轻”。";
    static final String TAGS = "gre; starter; weather";
    static final String EXAMPLE = "The storm began to abate.";

    private WordCard abate;

    /** The first new card of the starter deck, abate (减弱; 减少), with every detail filled in. */
    @BeforeEach
    void fillInAbate() throws SQLException {
        abate = services.wordRepository().findByEnglish(currentDeck().getId(), "abate").orElseThrow();
        abate.setPhonetic(PHONETIC);
        abate.setNote(NOTE);
        abate.setTags(TAGS);
        services.wordRepository().update(abate);
    }

    @Test
    void beforeSubmittingNoModeShowsWhatGivesTheAnswerAway() throws SQLException {
        selectTab("reviewTab");
        click("resetSessionButton");
        assertEquals("abate", text("reviewWordLabel"));
        assertEquals(PHONETIC + " · verb" + System.lineSeparator() + EXAMPLE, text("reviewHintLabel"));
        assertNoMeaningShown("English to Chinese");
        assertFalse(isVisible("reviewDetailsCard"));
        snapshot("en-to-zh");

        selectMode(ReviewMode.ZH_TO_EN);
        assertEquals("减弱; 减少", text("reviewWordLabel"));
        assertEquals("verb", text("reviewHintLabel"));
        assertNoEnglishShown("Chinese to English", "abate");
        snapshot("zh-to-en");

        selectMode(ReviewMode.MIXED);
        if (text("reviewWordLabel").equals("abate")) {
            assertNoMeaningShown("Mixed, English to Chinese");
        } else {
            assertEquals("减弱; 减少", text("reviewWordLabel"));
            assertNoEnglishShown("Mixed, Chinese to English", "abate");
        }

        selectMode(ReviewMode.CLOZE);
        assertEquals("The storm began to _____.", text("reviewWordLabel"));
        assertEquals("Hint: 减弱; 减少 · verb", text("reviewHintLabel"));
        assertNoEnglishShown("Cloze", "abate");
        snapshot("cloze");

        // A relearning card is weak, and its step is due: Weak Words asks it first, English to Chinese.
        abate.setState(CardState.RELEARNING);
        abate.setStability(1.0);
        abate.setDifficulty(6.0);
        abate.setRepetitions(3);
        abate.setLapses(1);
        abate.setLastReviewedAt(clock.now().minusMinutes(20));
        abate.setNextReviewAt(clock.now().minusMinutes(10));
        services.wordRepository().update(abate);
        selectMode(ReviewMode.WEAK_WORDS);
        assertEquals("abate", text("reviewWordLabel"));
        assertNoMeaningShown("Weak Words");
    }

    @Test
    void anExampleWithChineseInItIsNotShownBeforeAnEnglishToChineseAnswer() throws SQLException {
        abate.setExampleSentence(EXAMPLE + " 暴风雨开始减弱。");
        services.wordRepository().update(abate);
        selectTab("reviewTab");
        click("resetSessionButton");

        assertEquals(PHONETIC + " · verb", text("reviewHintLabel"));
        assertNoMeaningShown("English to Chinese");
    }

    @Test
    void theCheckedAnswerIsFollowedByTheWordsDetailsWithTheWordInBold() {
        selectTab("reviewTab");
        click("resetSessionButton");
        type("answerField", "减弱");
        pressEnter("answerField");
        waitForBackgroundTasks();

        assertTrue(isVisible("reviewDetailsCard"));
        assertEquals(PHONETIC, text("reviewDetailsPhonetic"));
        assertEquals("verb", text("reviewDetailsPos"));
        assertEquals(EXAMPLE, flowText("reviewDetailsExample"));
        assertEquals(List.of("abate"), boldParts("reviewDetailsExample"));
        assertEquals(NOTE, text("reviewDetailsNote"));
        assertEquals(TAGS, text("reviewDetailsTags"));
        assertFalse(isVisible("reviewDetailsTitle"), "the result above already names the word");
        snapshot("answered");

        click("rateGoodButton");

        assertFalse(isVisible("reviewDetailsCard"), "the next card's details wait for its answer");
    }

    @Test
    void aLongClozeSentenceIsShownWholeAndItsExampleWrapsBelowThePartOfSpeech() throws SQLException {
        abate.setExampleSentence("Although the forecasters had predicted that the storm would continue for several"
            + " days, by Tuesday morning the winds had begun to die down, and the residents, who had spent two"
            + " sleepless nights listening to the shutters rattle, saw that the damage had abated.");
        // A long note leaves the card less height than it would like.
        abate.setNote("vt. to reduce in amount, degree, or intensity\nvi. to decrease in force or intensity\n"
            + "law: to put an end to (a nuisance)\nlaw: to make void (a writ)\n"
            + "Synonyms: diminish, lessen, decline, subside, wane, ebb, recede, dwindle, taper off, let up");
        services.wordRepository().update(abate);
        selectTab("reviewTab");
        selectMode(ReviewMode.CLOZE);

        assertTrue(text("reviewWordLabel").endsWith("had _____."), text("reviewWordLabel"));
        type("answerField", "abated");
        pressEnter("answerField");
        waitForBackgroundTasks();

        // With the result and the details card below it, the sentence still gets all the lines it needs.
        assertGetsItsHeight(Fx.call(() -> find("reviewWordLabel", Label.class)), "the question, whose blank is at its end");
        TextFlow example = Fx.call(() -> find("reviewDetailsExample", TextFlow.class));
        assertTrue(Fx.call(() -> example.getChildren().size()) > 1);
        assertGetsItsHeight(example, "the wrapped example");
        double[] rows = Fx.call(() -> new double[] {
            bottom(find("reviewDetailsPos", Label.class)),
            top(example),
            bottom(example),
            top(find("reviewDetailsNote", Label.class))});
        assertTrue(rows[1] >= rows[0] - 0.5, "the example overlaps the part of speech: " + rows[1] + " < " + rows[0]);
        assertTrue(rows[3] >= rows[2] - 0.5, "the example overlaps the note: " + rows[3] + " < " + rows[2]);
        snapshot("long-cloze");
    }

    /** Asserts that {@code node}, laid out now, is as high as its wrapped text needs at its width. */
    private static void assertGetsItsHeight(Region node, String what) {
        double[] heights = Fx.call(() -> {
            Parent root = node.getScene().getRoot();
            root.applyCss();
            root.layout();
            return new double[] {node.getHeight(), node.prefHeight(node.getWidth())};
        });
        assertTrue(heights[0] >= heights[1] - 0.5, what + " is cut off: " + heights[0] + " < " + heights[1]);
    }

    private static double top(Node node) {
        return node.localToScene(node.getLayoutBounds()).getMinY();
    }

    private static double bottom(Node node) {
        return node.localToScene(node.getLayoutBounds()).getMaxY();
    }

    @Test
    void anInflectedFormInTheExampleIsShownInBold() throws SQLException {
        long deckId = currentDeck().getId();
        WordCard admonish = services.wordRepository().findByEnglish(deckId, "admonish").orElseThrow();
        selectTab("wordListTab");
        selectWord("admonish");

        assertEquals(admonish.getExampleSentence(), flowText("wordDetailsExample"));
        assertEquals(List.of("admonished"), boldParts("wordDetailsExample"));
    }

    @Test
    void theWordListShowsTheSelectedWordsDetailsAndTheEditDialogEditsItsPhonetic() throws SQLException {
        selectTab("wordListTab");
        assertTrue(text("wordDetailsEmpty").startsWith("Select a word"), text("wordDetailsEmpty"));

        selectWord("abate");

        assertEquals("abate   减弱; 减少", text("wordDetailsTitle"));
        assertEquals(PHONETIC, text("wordDetailsPhonetic"));
        assertEquals(EXAMPLE, flowText("wordDetailsExample"));
        assertEquals(List.of("abate"), boldParts("wordDetailsExample"));
        assertEquals(NOTE, text("wordDetailsNote"));
        assertEquals(TAGS, text("wordDetailsTags"));
        assertFalse(isVisible("wordDetailsEmpty"));
        snapshot("details");

        dialogs.submitForm(form -> {
            TextField phonetic = (TextField) form.lookup("#editPhoneticField");
            assertEquals(PHONETIC, phonetic.getText());
            phonetic.setText("/əˈbeɪt/ (US)");
        });
        click("editWordButton");

        assertEquals("/əˈbeɪt/ (US)", services.wordRepository().findById(abate.getId()).orElseThrow().getPhonetic());
        assertEquals("/əˈbeɪt/ (US)", text("wordDetailsPhonetic"), "the edited word stays selected");
        assertEquals("abate", Fx.call(() -> this.<WordCard>table("wordTable").getSelectionModel().getSelectedItem()
            .getEnglish()));
    }

    @Test
    void aWordWithoutDetailsSaysHowToAddThem() throws SQLException {
        services.wordRepository().insert(WordCard.createNew(currentDeck().getId(), "petrichor", "雨后泥土的气味", clock.now()));
        selectTab("wordListTab");

        selectWord("petrichor");

        assertEquals("petrichor   雨后泥土的气味", text("wordDetailsTitle"));
        assertTrue(text("wordDetailsEmpty").startsWith("No phonetic, part of speech, example, note or tags yet"),
            text("wordDetailsEmpty"));
        for (String row : List.of("wordDetailsPhonetic", "wordDetailsPos", "wordDetailsExample", "wordDetailsNote",
            "wordDetailsTags")) {
            assertFalse(isVisible(row), row);
        }
    }

    private void selectMode(ReviewMode mode) {
        this.<ReviewMode>select("reviewModeSelector", mode::equals);
    }

    /** Selects the word in the Word List, refreshed first, since the test changed words behind its back. */
    private void selectWord(String english) {
        click("refreshWordsButton");
        Fx.run(() -> {
            var table = this.<WordCard>table("wordTable");
            WordCard word = table.getItems().stream()
                .filter(item -> item.getEnglish().equals(english))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No row " + english));
            table.getSelectionModel().select(word);
        });
    }

    /** Neither Chinese meaning of abate, its note nor its tags is on the Review tab. */
    private void assertNoMeaningShown(String mode) {
        String shown = String.join(" | ", visibleTexts("reviewTab"));
        for (String secret : List.of("减弱", "减少", NOTE, TAGS)) {
            assertFalse(shown.contains(secret), mode + " shows \"" + secret + "\" before the answer: " + shown);
        }
    }

    /** Neither the English word, its phonetic, its example, its note nor its tags is on the Review tab. */
    private void assertNoEnglishShown(String mode, String... forms) {
        String shown = String.join(" | ", visibleTexts("reviewTab"));
        List<String> secrets = new ArrayList<>(List.of(forms));
        secrets.addAll(List.of(PHONETIC, NOTE, TAGS));
        for (String secret : secrets) {
            assertFalse(shown.toLowerCase(Locale.ROOT).contains(secret.toLowerCase(Locale.ROOT)),
                mode + " shows \"" + secret + "\" before the answer: " + shown);
        }
    }

    /** Every text a user can see on the tab: labels, buttons, fields (and their prompts) and text flows. */
    List<String> visibleTexts(String tabId) {
        return Fx.call(() -> {
            List<String> texts = new ArrayList<>();
            collectVisibleTexts(tab(tabId).getContent(), texts);
            return texts;
        });
    }

    private static void collectVisibleTexts(Node node, List<String> texts) {
        if (node == null || !node.isVisible()) {
            return;
        }
        if (node instanceof Labeled labeled) {
            texts.add(labeled.getText());
        } else if (node instanceof TextInputControl input) {
            texts.add(input.getText());
            texts.add(input.getPromptText());
        } else if (node instanceof Text text) {
            texts.add(text.getText());
        }
        if (node instanceof Parent parent && !(node instanceof Control)) {
            parent.getChildrenUnmodifiable().forEach(child -> collectVisibleTexts(child, texts));
        }
    }

    String flowText(String id) {
        return Fx.call(() -> find(id, TextFlow.class).getChildren().stream()
            .map(node -> ((Text) node).getText())
            .collect(Collectors.joining()));
    }

    List<String> boldParts(String id) {
        return Fx.call(() -> find(id, TextFlow.class).getChildren().stream()
            .filter(node -> node.getStyleClass().contains(WordDetailsCard.TARGET_STYLE_CLASS))
            .map(node -> ((Text) node).getText())
            .toList());
    }
}
