package com.vocabtrainer.ui;

import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.WordCard;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
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

        selectTab("dashboardTab");
        assertEquals(String.valueOf(STARTER_WORDS + 1), text("totalWordsLabel"));
        assertEquals("0 / 5", text("newWordsTodayLabel"), "a word counts as new when it is first reviewed");
        assertEquals("0", text("xpLabel"), "adding a word earns no XP");
        selectTab("wordListTab");
        assertEquals(STARTER_WORDS + 1, rowCount("wordTable"));
        assertTrue(wordListEnglish().contains("obfuscate"));
        assertTrue(dialogs.shown().isEmpty(), dialogs.shown().toString());
    }

    @Test
    void theDictionaryFillsInAPhoneticTheFormLeftEmpty() throws Exception {
        selectTab("addImportTab");
        type("addEnglishField", "obfuscate");
        type("addChineseField", "使模糊");
        click("addWordButton");

        waitForText("addWordStatusLabel", "Added to " + STARTER_DECK + ": obfuscate | Verified by "
            + TestDictionary.SOURCE + " | Phonetic /ˈɒbfʌskeɪt/");
        long deckId = currentDeck().getId();
        assertEquals("/ˈɒbfʌskeɪt/", services.wordRepository().findByEnglish(deckId, "obfuscate").orElseThrow().getPhonetic());
        assertEquals("", text("addPhoneticField"), "the form is cleared after adding");

        // A phonetic the user typed is kept.
        services.wordRepository().deleteById(services.wordRepository().findByEnglish(deckId, "obfuscate").orElseThrow().getId());
        type("addEnglishField", "obfuscate");
        type("addChineseField", "使模糊");
        type("addPhoneticField", "/ɒbˈfʌskeɪt/");
        click("addWordButton");

        waitForText("addWordStatusLabel", "Added to " + STARTER_DECK + ": obfuscate | Verified by " + TestDictionary.SOURCE);
        assertEquals("/ɒbˈfʌskeɪt/", services.wordRepository().findByEnglish(deckId, "obfuscate").orElseThrow().getPhonetic());
    }

    @Test
    void aWordNoDictionaryKnowsIsAddedAsUnverifiedOnlyAfterConfirmation() throws Exception {
        selectTab("addImportTab");
        type("addEnglishField", "snarkle");
        type("addChineseField", "测试词");
        dialogs.confirm(false);
        click("addWordButton");

        // The dictionaries are asked in the background, then the question is asked.
        waitForText("addWordStatusLabel", "Canceled: snarkle");
        ScriptedDialogs.Shown question = dialogs.last(ScriptedDialogs.Kind.CONFIRM);
        assertEquals("词条未找到", question.title());
        assertEquals("词条未找到：snarkle", question.header());
        assertTrue(question.content().endsWith("是否强制添加并标记为 UNVERIFIED？"), question.content());
        assertTrue(services.wordRepository().findByEnglish(currentDeck().getId(), "snarkle").isEmpty());
        assertEquals("snarkle", text("addEnglishField"));

        dialogs.confirm(true);
        click("addWordButton");

        waitForText("addWordStatusLabel", "Added to " + STARTER_DECK + ": snarkle | Marked UNVERIFIED");
        WordCard saved = services.wordRepository().findByEnglish(currentDeck().getId(), "snarkle").orElseThrow();
        assertEquals("UNVERIFIED", saved.getTags());
        selectTab("dashboardTab");
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
        selectTab("dashboardTab");
        assertEquals(String.valueOf(STARTER_WORDS), text("totalWordsLabel"));
    }

    @Test
    void invalidInputIsExplainedNextToTheForm() {
        selectTab("addImportTab");
        type("addEnglishField", "obfuscate");
        click("addWordButton");

        assertEquals("Chinese meaning cannot be empty.", text("addWordStatusLabel"));
        selectTab("dashboardTab");
        assertEquals(String.valueOf(STARTER_WORDS), text("totalWordsLabel"));
    }

    @Test
    void theWordListFiltersBySearchTextStatusTagAndPartOfSpeech() throws Exception {
        // Every starter word is new: due, not weak and not mastered. Three reviewed words with their
        // own tag tell whether the status and tag filters filter at all.
        long deckId = currentDeck().getId();
        services.wordRepository().insert(reviewed(deckId, "petrichor", "雨后泥土的气味", 30, 5, 3, 3, 0));
        services.wordRepository().insert(reviewed(deckId, "obstinate", "固执的", 3, 6, 4, 1, 1));
        WordCard leech = reviewed(deckId, "cavil", "挑剔", 2, 9, 20, 3, 8);
        leech.setTags("mine; leech");
        services.wordRepository().insert(leech);
        selectTab("wordListTab");
        click("refreshWordsButton");
        List<WordCard> words = services.wordRepository().findAll(currentDeck().getId());
        assertEquals(STARTER_WORDS + 3, words.size());
        assertEquals(STARTER_WORDS + 3, rowCount("wordTable"));
        // English, Chinese, Next review, Interval, Memory, Status.
        assertEquals("30 days", cell("wordTable", "petrichor", 3));
        // Reviewed yesterday with a 30-day stability: almost certainly still remembered.
        assertEquals("100%", cell("wordTable", "petrichor", 4));
        assertEquals("Mastered", cell("wordTable", "petrichor", 5));
        assertEquals("-", cell("wordTable", "abate", 3));
        assertEquals("New", cell("wordTable", "abate", 4));
        assertEquals("Due", cell("wordTable", "abate", 5));

        // Typing is debounced, so wait for the table to catch up.
        type("wordSearchField", "abate");
        waitForWordList("search 'abate'", List.of("abate"));

        type("wordSearchField", "");
        waitForRowCount(STARTER_WORDS + 3);

        Fx.run(() -> this.<String>comboBox("wordStatusFilter").getSelectionModel().select("Mastered"));
        assertEquals(List.of("petrichor"), wordListEnglish());
        Fx.run(() -> this.<String>comboBox("wordStatusFilter").getSelectionModel().select("Due"));
        assertEquals(STARTER_WORDS, rowCount("wordTable"));
        assertFalse(wordListEnglish().contains("petrichor"));
        // Weak: failed within the last three reviews, or hard and not mastered yet.
        Fx.run(() -> this.<String>comboBox("wordStatusFilter").getSelectionModel().select("Weak"));
        assertEquals(List.of("cavil", "obstinate"), wordListEnglish());
        Fx.run(() -> this.<String>comboBox("wordStatusFilter").getSelectionModel().select("Leech"));
        assertEquals(List.of("cavil"), wordListEnglish());
        Fx.run(() -> this.<String>comboBox("wordStatusFilter").getSelectionModel().select("All"));
        assertEquals(STARTER_WORDS + 3, rowCount("wordTable"));

        type("wordPosFilterField", "noun");
        waitForWordList("POS filter 'noun'", englishOf(words, word -> contains(word.getPartOfSpeech(), "noun")));
        type("wordPosFilterField", "");
        waitForRowCount(STARTER_WORDS + 3);

        type("wordTagFilterField", "MINE");
        waitForWordList("tag filter 'MINE'", List.of("cavil", "obstinate", "petrichor"));
        type("wordTagFilterField", "GRE");
        waitForWordList("tag filter 'GRE'", englishOf(words, word -> contains(word.getTags(), "gre")));
        type("wordTagFilterField", "no-such-tag");
        waitForRowCount(0);
    }

    /** A word in review: its memory, review counts and lapses, last reviewed when its stability says it is due. */
    private static WordCard reviewed(long deckId, String english, String chinese, double stability, double difficulty,
                                     int repetitions, int consecutiveCorrect, int lapses) {
        WordCard word = WordCard.createNew(deckId, english, chinese);
        word.setPartOfSpeech("noun");
        word.setTags("mine");
        word.setState(CardState.REVIEW);
        word.setStability(stability);
        word.setDifficulty(difficulty);
        word.setIntervalDays((int) Math.round(stability));
        word.setRepetitions(repetitions);
        word.setConsecutiveCorrect(consecutiveCorrect);
        word.setLapses(lapses);
        word.setLastReviewedAt(LocalDateTime.now().minusDays(1));
        word.setNextReviewAt(LocalDateTime.now().plusDays(Math.round(stability)));
        return word;
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
