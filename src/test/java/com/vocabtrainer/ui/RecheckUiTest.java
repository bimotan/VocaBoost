package com.vocabtrainer.ui;

import com.vocabtrainer.app.AppServices;
import com.vocabtrainer.domain.DictionaryLookupResult;
import com.vocabtrainer.domain.LookupOutcome;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.service.CompositeDictionaryService;
import com.vocabtrainer.service.DictionaryService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "Re-check unchecked words" and the Unchecked filter on the Word List (review finding D5). */
@Tag("ui")
class RecheckUiTest extends MainWindowUiTest {
    @Override
    AppServices.Builder configure(AppServices.Builder builder) {
        return builder.dictionaryService((cache, local) ->
            new CompositeDictionaryService(List.of(local, new TestDictionary(), new UnreachableFor("petrichor"))));
    }

    @Test
    void uncheckedWordsAreCheckedAgainAndRetagged() throws Exception {
        long deckId = currentDeck().getId();
        WordCard obfuscate = add(deckId, "obfuscate", "UNCHECKED");
        WordCard zzyzx = add(deckId, "zzyzx", "mine; UNCHECKED");
        WordCard petrichor = add(deckId, "petrichor", "UNCHECKED");
        selectTab("wordListTab");
        click("refreshWordsButton");
        Fx.run(() -> this.<String>comboBox("wordStatusFilter").getSelectionModel().select("Unchecked"));
        assertEquals(3, rowCount("wordTable"));
        assertEquals("Unchecked", shownValue("wordStatusFilter"));

        click("recheckUncheckedButton");
        waitForBackgroundTasks();

        assertEquals("Re-checked 3 words: 1 verified, 1 in no dictionary (tagged UNVERIFIED), 1 still unchecked.",
            text("recheckStatusLabel"));
        assertEquals("VERIFIED; Test dictionary", reread(obfuscate).getTags());
        assertEquals("/ˈɒbfʌskeɪt/", reread(obfuscate).getPhonetic());
        assertEquals("mine; UNVERIFIED", reread(zzyzx).getTags());
        assertEquals("UNCHECKED", reread(petrichor).getTags());
        assertEquals(1, rowCount("wordTable"), "the list follows: only petrichor is still unchecked");
        assertTrue(isVisible("recheckUncheckedButton") && !isDisabled("recheckUncheckedButton"));
        assertTrue(!isVisible("recheckProgressBar"), "the progress bar is gone");
        snapshot("rechecked");
    }

    @Test
    void allDecksChecksEveryActiveDeck() throws Exception {
        long starter = currentDeck().getId();
        WordCard here = add(starter, "obfuscate", "UNCHECKED");
        dialogs.answerText("Other");
        click("newDeckButton");
        long other = currentDeck().getId();
        WordCard there = add(other, "zzyzx", "UNCHECKED");
        selectTab("wordListTab");

        click("recheckUncheckedButton");
        waitForBackgroundTasks();
        assertEquals("UNCHECKED", reread(here).getTags(), "only the current deck");
        assertEquals("UNVERIFIED", reread(there).getTags());

        click("wordAllDecksToggle");
        click("recheckUncheckedButton");
        waitForBackgroundTasks();
        assertEquals("VERIFIED; Test dictionary", reread(here).getTags());
        assertEquals("Re-checked 1 word: 1 verified, 0 in no dictionary (tagged UNVERIFIED), 0 still unchecked.",
            text("recheckStatusLabel"));
    }

    @Test
    void offlineTheResultSaysOnlyTheOfflineDictionariesWereAsked() throws Exception {
        add(currentDeck().getId(), "petrichor", "UNCHECKED");
        click("offlineModeToggle");
        selectTab("wordListTab");

        click("recheckUncheckedButton");
        waitForBackgroundTasks();

        assertTrue(text("recheckStatusLabel").endsWith("Offline mode is on, so only the offline dictionaries were asked."),
            text("recheckStatusLabel"));
    }

    private WordCard add(long deckId, String english, String tags) throws SQLException {
        WordCard word = WordCard.createNew(deckId, english, "释义", clock.now());
        word.setTags(tags);
        return services.wordRepository().insert(word);
    }

    private WordCard reread(WordCard word) throws SQLException {
        return services.wordRepository().findById(word.getId()).orElseThrow();
    }

    /** A dictionary that cannot be reached for one word and does not have the others. */
    private record UnreachableFor(String word) implements DictionaryService {
        @Override
        public DictionaryLookupResult lookup(String english) {
            return word.equalsIgnoreCase(english)
                ? DictionaryLookupResult.unavailable(LookupOutcome.NETWORK_ERROR, "Online dictionaries: cannot connect")
                : DictionaryLookupResult.notFound("Not found online.");
        }

        @Override
        public boolean isConfigured() {
            return true;
        }
    }
}
