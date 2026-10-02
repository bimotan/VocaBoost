package com.vocabtrainer.ui;

import com.vocabtrainer.domain.WordCard;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("ui")
class WordUiTest extends MainWindowUiTest {
    @Test
    void aWordAddedFromTheFormIsVerifiedOfflineAndAppearsInTheWordList() throws Exception {
        selectTab("addImportTab");
        type("addEnglishField", "obfuscate");
        type("addChineseField", "使模糊");
        type("addPosField", "verb");
        type("addTagsField", "mine");
        type("addExampleArea", "Jargon can obfuscate a simple idea.");
        click("addWordButton");

        waitForTextStartingWith("addWordStatusLabel", "Added to " + STARTER_DECK + ": obfuscate | Verified by "
            + TestDictionary.SOURCE);
        WordCard saved = services.wordRepository().findByEnglish(currentDeck().getId(), "obfuscate").orElseThrow();
        assertEquals("使模糊", saved.getChinese());
        assertEquals("verb", saved.getPartOfSpeech());
        assertEquals("mine; VERIFIED; " + TestDictionary.SOURCE, saved.getTags());
        for (String field : List.of("addEnglishField", "addChineseField", "addPosField", "addTagsField", "addExampleArea")) {
            assertEquals("", text(field), field);
        }

        assertEquals(String.valueOf(STARTER_WORDS + 1), text("totalWordsLabel"));
        assertEquals("1 / 5", text("newWordsTodayLabel"));
        assertEquals(STARTER_WORDS + 1, rowCount("wordTable"));
        assertTrue(wordListEnglish().contains("obfuscate"));
        assertTrue(dialogs.shown().isEmpty(), dialogs.shown().toString());
    }

    @Test
    void aWordNoDictionaryKnowsIsAddedAsUnverifiedOnlyAfterConfirmation() throws Exception {
        selectTab("addImportTab");
        type("addEnglishField", "snarkle");
        type("addChineseField", "测试词");
        dialogs.confirm(false);
        click("addWordButton");

        ScriptedDialogs.Shown question = dialogs.last(ScriptedDialogs.Kind.CONFIRM);
        assertEquals("词条未找到", question.title());
        assertEquals("词条未找到：snarkle", question.header());
        assertTrue(question.content().endsWith("是否强制添加并标记为 UNVERIFIED？"), question.content());
        waitForText("addWordStatusLabel", "Canceled: snarkle");
        assertTrue(services.wordRepository().findByEnglish(currentDeck().getId(), "snarkle").isEmpty());
        assertEquals("snarkle", text("addEnglishField"));

        dialogs.confirm(true);
        click("addWordButton");

        waitForText("addWordStatusLabel", "Added to " + STARTER_DECK + ": snarkle | Marked UNVERIFIED");
        WordCard saved = services.wordRepository().findByEnglish(currentDeck().getId(), "snarkle").orElseThrow();
        assertEquals("UNVERIFIED", saved.getTags());
        assertEquals(String.valueOf(STARTER_WORDS + 1), text("totalWordsLabel"));

        selectTab("wordListTab");
        Fx.run(() -> this.<String>comboBox("wordStatusFilter").getSelectionModel().select("Unverified"));
        assertEquals(List.of("snarkle"), wordListEnglish());
    }

    @Test
    void aWordAlreadyInTheDeckIsNotAddedAgain() {
        selectTab("addImportTab");
        type("addEnglishField", "abate");
        type("addChineseField", "减弱");
        click("addWordButton");

        waitForText("addWordStatusLabel", "Word already exists in " + STARTER_DECK + ": abate. Edit it in Word List.");
        assertEquals("abate", text("addEnglishField"));
        assertEquals(String.valueOf(STARTER_WORDS), text("totalWordsLabel"));
    }

    @Test
    void invalidInputIsExplainedNextToTheForm() {
        selectTab("addImportTab");
        type("addEnglishField", "obfuscate");
        click("addWordButton");

        assertEquals("Chinese meaning cannot be empty.", text("addWordStatusLabel"));
        assertEquals(String.valueOf(STARTER_WORDS), text("totalWordsLabel"));
    }

    @Test
    void theWordListFiltersBySearchTextStatusTagAndPartOfSpeech() throws Exception {
        selectTab("wordListTab");
        List<WordCard> words = services.wordRepository().findAll(currentDeck().getId());
        assertEquals(STARTER_WORDS, rowCount("wordTable"));

        // Typing is debounced, so wait for the table to catch up.
        type("wordSearchField", "abate");
        waitForWordList("search 'abate'", List.of("abate"));

        type("wordSearchField", "");
        waitForRowCount(STARTER_WORDS);

        Fx.run(() -> this.<String>comboBox("wordStatusFilter").getSelectionModel().select("Mastered"));
        assertEquals(0, rowCount("wordTable"));
        Fx.run(() -> this.<String>comboBox("wordStatusFilter").getSelectionModel().select("Due"));
        assertEquals(STARTER_WORDS, rowCount("wordTable"));
        Fx.run(() -> this.<String>comboBox("wordStatusFilter").getSelectionModel().select("Weak"));
        assertEquals(STARTER_WORDS, rowCount("wordTable"));
        Fx.run(() -> this.<String>comboBox("wordStatusFilter").getSelectionModel().select("All"));

        type("wordPosFilterField", "noun");
        waitForWordList("POS filter 'noun'", englishOf(words, word -> contains(word.getPartOfSpeech(), "noun")));
        type("wordPosFilterField", "");
        waitForRowCount(STARTER_WORDS);

        type("wordTagFilterField", "GRE");
        waitForWordList("tag filter 'GRE'", englishOf(words, word -> contains(word.getTags(), "gre")));
        type("wordTagFilterField", "no-such-tag");
        waitForRowCount(0);
    }

    private List<String> wordListEnglish() {
        return Fx.call(() -> this.<WordCard>table("wordTable").getItems().stream().map(WordCard::getEnglish).toList());
    }

    private void waitForWordList(String description, List<String> expected) {
        assertFalse(expected.isEmpty(), "expected rows for " + description);
        Fx.waitUntil("the word list shows " + expected + " after " + description,
            () -> wordListEnglish().equals(expected));
    }

    private void waitForRowCount(int expected) {
        Fx.waitUntil("the word list has " + expected + " rows", () -> rowCount("wordTable") == expected);
    }

    private static List<String> englishOf(List<WordCard> words, Predicate<WordCard> filter) {
        return words.stream().filter(filter).map(WordCard::getEnglish).toList();
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }
}
