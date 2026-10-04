package com.vocabtrainer.service;

import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.DictionaryEntry;
import com.vocabtrainer.domain.DictionaryLookupResult;
import com.vocabtrainer.domain.LookupOutcome;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.TestDatabases;
import com.vocabtrainer.repository.WordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Words added while the dictionaries could not be asked are checked again (review finding D5):
 * retagged verified or unverified, kept unchecked otherwise, without overwriting edits or reviews.
 */
class UncheckedWordsServiceTest {
    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    private final ScriptedDictionary dictionary = new ScriptedDictionary();
    private final AtomicBoolean offline = new AtomicBoolean();
    private WordRepository words;
    private UncheckedWordsService service;
    private long deckId;

    @BeforeEach
    void setUp() throws SQLException {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("unchecked.db"));
        words = new WordRepository(databaseManager);
        service = new UncheckedWordsService(words, () -> dictionary, offline::get);
        deckId = new com.vocabtrainer.repository.DeckRepository(databaseManager).create("GRE").getId();
    }

    @Test
    void eachWordIsRetaggedByWhatTheDictionariesSayNow() throws SQLException {
        WordCard found = add("obfuscate", "gre; UNCHECKED", "");
        WordCard missing = add("zzyzx", "UNCHECKED", "");
        WordCard unreachable = add("petrichor", "mine, unchecked", "");
        WordCard alreadyFine = add("lucid", "gre; VERIFIED", "");
        dictionary.found("obfuscate", "Test dictionary", "/ˈɒbfʌskeɪt/");
        dictionary.missing("zzyzx");
        dictionary.unavailable("petrichor", LookupOutcome.NETWORK_ERROR);
        List<UncheckedWordsService.Progress> progress = new ArrayList<>();

        UncheckedWordsService.Result result = service.recheck(List.of(deckId), progress::add, () -> false);

        assertEquals(new UncheckedWordsService.Result(3, 1, 1, 1, false, "petrichor: cannot connect", false), result);
        assertEquals("gre; VERIFIED; Test dictionary", reread(found).getTags());
        assertEquals("/ˈɒbfʌskeɪt/", reread(found).getPhonetic(), "an empty phonetic is filled");
        assertEquals("UNVERIFIED", reread(missing).getTags());
        assertEquals("mine, unchecked", reread(unreachable).getTags(), "still unchecked, untouched");
        assertEquals("gre; VERIFIED", reread(alreadyFine).getTags());
        assertEquals(List.of("obfuscate", "petrichor", "zzyzx"), dictionary.asked, "only the unchecked words");
        assertEquals(new UncheckedWordsService.Progress(3, 3), progress.get(progress.size() - 1));
        assertEquals("Re-checked 3 words: 1 verified, 1 in no dictionary (tagged UNVERIFIED), 1 still unchecked.",
            result.toDisplayText());
    }

    @Test
    void onlyTheTagsAndAnEmptyPhoneticAreWrittenNeverTheSchedule() throws SQLException {
        WordCard word = add("obfuscate", "UNCHECKED", "/own/");
        dictionary.found("obfuscate", "Test dictionary", "/ˈɒbfʌskeɪt/");
        dictionary.duringLookup = () -> {
            // A review saved while the word is being checked.
            WordCard reviewed = reread(word);
            reviewed.setState(CardState.REVIEW);
            reviewed.setStability(12);
            reviewed.setNextReviewAt(LocalDateTime.now().plusDays(12));
            update(reviewed);
        };

        service.recheck(List.of(deckId), progress -> { }, () -> false);

        WordCard saved = reread(word);
        assertEquals(CardState.REVIEW, saved.getState(), "the review stays");
        assertEquals(12, saved.getStability());
        assertEquals("/own/", saved.getPhonetic(), "a phonetic of its own is kept");
        assertEquals("VERIFIED; Test dictionary", saved.getTags());
    }

    @Test
    void tagsEditedWhileTheWordIsCheckedAreKept() throws SQLException {
        WordCard word = add("obfuscate", "UNCHECKED", "");
        dictionary.found("obfuscate", "Test dictionary", "");
        dictionary.duringLookup = () -> {
            WordCard edited = reread(word);
            edited.setTags("my own tags");
            update(edited);
        };

        UncheckedWordsService.Result result = service.recheck(List.of(deckId), progress -> { }, () -> false);

        assertEquals("my own tags", reread(word).getTags());
        assertEquals(0, result.verified());
        assertEquals(1, result.stillUnchecked());
    }

    @Test
    void itStopsAfterThreeWordsInARowCouldNotBeChecked() throws SQLException {
        for (String english : List.of("alpha", "bravo", "charlie", "delta", "echo")) {
            add(english, "UNCHECKED", "");
            dictionary.unavailable(english, LookupOutcome.TIMEOUT);
        }

        UncheckedWordsService.Result result = service.recheck(List.of(deckId), progress -> { }, () -> false);

        assertEquals(List.of("alpha", "bravo", "charlie"), dictionary.asked);
        assertTrue(result.stopped());
        assertEquals(5, result.stillUnchecked());
        assertTrue(result.toDisplayText().contains("Stopped early because the dictionaries cannot be asked: charlie"),
            result.toDisplayText());
    }

    @Test
    void offlineOnlyTheOfflineDictionariesAnswerAndTheResultSaysSo() throws SQLException {
        offline.set(true);
        WordCard local = add("abate", "UNCHECKED", "");
        add("petrichor", "UNCHECKED", "");
        dictionary.found("abate", "Bundled GRE starter", "");
        dictionary.unavailable("petrichor", LookupOutcome.OFFLINE);

        UncheckedWordsService.Result result = service.recheck(List.of(deckId), progress -> { }, () -> false);

        assertEquals("VERIFIED; Bundled GRE starter", reread(local).getTags());
        assertFalse(result.stopped(), "offline is no reason to stop: a later word may be in ECDICT");
        assertTrue(result.toDisplayText().endsWith("Offline mode is on, so only the offline dictionaries were asked."),
            result.toDisplayText());
    }

    @Test
    void cancellingKeepsTheWordsCheckedSoFar() throws SQLException {
        WordCard first = add("alpha", "UNCHECKED", "");
        WordCard second = add("bravo", "UNCHECKED", "");
        dictionary.found("alpha", "Test dictionary", "");
        dictionary.found("bravo", "Test dictionary", "");
        AtomicBoolean cancel = new AtomicBoolean();

        assertThrows(CancellationException.class, () -> service.recheck(List.of(deckId),
            progress -> cancel.set(progress.checked() == 1), cancel::get));

        assertEquals("VERIFIED; Test dictionary", reread(first).getTags());
        assertEquals("UNCHECKED", reread(second).getTags());
    }

    @Test
    void nothingToCheckSaysSo() {
        UncheckedWordsService.Result result = service.recheck(List.of(deckId), progress -> { }, () -> false);

        assertEquals("No word is tagged UNCHECKED.", result.toDisplayText());
        assertEquals(List.of(), dictionary.asked);
    }

    @Test
    void verificationTagsReplaceEachOther() {
        assertEquals("gre; UNVERIFIED", UncheckedWordsService.retagged("gre, unchecked", "UNVERIFIED"));
        assertEquals("VERIFIED; ECDICT", UncheckedWordsService.verifiedTags("UNVERIFIED;UNCHECKED", "ECDICT"));
        assertEquals("a; b; VERIFIED", UncheckedWordsService.verifiedTags(" a ;; b ", ""));
    }

    private WordCard add(String english, String tags, String phonetic) throws SQLException {
        WordCard word = WordCard.createNew(deckId, english, "释义");
        word.setTags(tags);
        word.setPhonetic(phonetic);
        return words.insert(word);
    }

    private WordCard reread(WordCard word) {
        try {
            return words.findById(word.getId()).orElseThrow();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void update(WordCard word) {
        try {
            words.update(word);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Answers each word as scripted; {@link #duringLookup} runs inside every lookup. */
    static final class ScriptedDictionary implements DictionaryService {
        final Map<String, DictionaryLookupResult> answers = new HashMap<>();
        final List<String> asked = new ArrayList<>();
        Runnable duringLookup = () -> { };

        void found(String english, String source, String phonetic) {
            answers.put(english, DictionaryLookupResult.success("Loaded.",
                List.of(new DictionaryEntry(english, "释义", "", phonetic, "", source))));
        }

        void missing(String english) {
            answers.put(english, DictionaryLookupResult.notFound(english + ": not found"));
        }

        void unavailable(String english, LookupOutcome outcome) {
            answers.put(english, DictionaryLookupResult.unavailable(outcome, english + ": cannot connect"));
        }

        @Override
        public DictionaryLookupResult lookup(String english) {
            asked.add(english);
            duringLookup.run();
            return answers.getOrDefault(english, DictionaryLookupResult.notFound("not scripted"));
        }

        @Override
        public boolean isConfigured() {
            return true;
        }
    }
}
