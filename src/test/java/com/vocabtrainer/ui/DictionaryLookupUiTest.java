package com.vocabtrainer.ui;

import com.vocabtrainer.app.AppServices;
import com.vocabtrainer.domain.DictionaryEntry;
import com.vocabtrainer.domain.DictionaryLookupResult;
import com.vocabtrainer.domain.LookupOutcome;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.service.CompositeDictionaryService;
import com.vocabtrainer.service.DictionaryService;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dictionary lookups and the check before adding a word run in the background (review findings
 * C3, D4, D5): the window keeps working while a dictionary is slow, a result for a word the user
 * has since changed is dropped, and a dictionary that cannot be asked is not reported as a word
 * that does not exist.
 */
@Tag("ui")
class DictionaryLookupUiTest extends MainWindowUiTest {
    private final SlowDictionary dictionary = new SlowDictionary();

    @Override
    AppServices.Builder configure(AppServices.Builder builder) {
        return builder.dictionaryService((cache, local) -> new CompositeDictionaryService(List.of(local, dictionary)));
    }

    /** Runs before the base class waits for background tasks, so a failed test cannot leave one waiting. */
    @AfterEach
    void releaseHeldLookups() {
        dictionary.releaseAll();
    }

    @Test
    void aSlowLookupLeavesTheWindowResponsiveAndAResultForAnOlderWordIsDropped() throws Exception {
        dictionary.answer("petrichor", found("petrichor", "雨后泥土的气味"));
        dictionary.answer("obfuscate", found("obfuscate", "使模糊"));
        // Ignores the interruption, so its result arrives after the user moved on.
        Hold slow = dictionary.hold("petrichor", true);
        selectTab("addImportTab");
        type("lookupField", "petrichor");
        click("lookupButton");
        slow.awaitStarted();

        assertEquals("Looking up petrichor...", text("lookupStatusLabel"));
        assertTrue(isVisible("lookupBusyIndicator"));
        assertFalse(isDisabled("lookupButton"));
        assertFalse(isDisabled("addWordButton"));
        // The JavaFX thread is free: the window takes keyboard input while the dictionary is still busy.
        type("addNoteArea", "");
        pressKey("addNoteArea", KeyCode.A);
        assertEquals("a", text("addNoteArea"));

        type("lookupField", "obfuscate");
        assertEquals("Lookup canceled: the word changed.", text("lookupStatusLabel"));
        assertFalse(isVisible("lookupBusyIndicator"));
        click("lookupButton");
        waitForText("lookupStatusLabel", "Loaded from the slow dictionary.");
        assertEquals("obfuscate", text("addEnglishField"));

        slow.release();
        waitForBackgroundTasks();

        assertEquals("Loaded from the slow dictionary.", text("lookupStatusLabel"));
        assertEquals(List.of("obfuscate"), lookupResults());
        assertEquals("obfuscate", text("addEnglishField"));
        assertEquals("使模糊", text("addChineseField"));
    }

    @Test
    void startingAnotherLookupInterruptsTheOneStillRunning() throws Exception {
        Hold slow = dictionary.hold("petrichor", false);
        selectTab("addImportTab");
        type("lookupField", "petrichor");
        pressEnter("lookupField");
        slow.awaitStarted();

        click("refreshLookupButton");

        Fx.waitUntil("the first lookup is interrupted", () -> dictionary.interrupted.contains("petrichor"));
        waitForBackgroundTasks();
        assertTrue(text("lookupStatusLabel").startsWith("Not found."), text("lookupStatusLabel"));
    }

    @Test
    void theLookupBoxTellsAMissingWordFromADictionaryThatCannotBeAskedAndOffersRetry() {
        dictionary.answer("quokka",
            DictionaryLookupResult.unavailable(LookupOutcome.RATE_LIMITED, "dictionaryapi.dev: too many requests (HTTP 429): try again later."),
            found("quokka", "短尾矮袋鼠"));
        selectTab("addImportTab");

        type("lookupField", "snarkle");
        click("lookupButton");
        waitForBackgroundTasks();
        assertTrue(text("lookupStatusLabel").startsWith("Not found."), text("lookupStatusLabel"));
        assertFalse(isVisible("lookupRetryButton"));

        type("lookupField", "quokka");
        click("lookupButton");
        waitForBackgroundTasks();
        assertEquals("The online dictionary limits how often it may be asked: try again later." + System.lineSeparator()
            + "Not found: the local dictionary does not have this word. | dictionaryapi.dev: too many requests (HTTP 429): try again later.",
            text("lookupStatusLabel"));
        assertTrue(isVisible("lookupRetryButton"));
        snapshot("rate-limited");

        click("lookupRetryButton");
        waitForBackgroundTasks();
        assertEquals("Loaded from the slow dictionary.", text("lookupStatusLabel"));
        assertEquals(List.of("quokka"), lookupResults());
        assertFalse(isVisible("lookupRetryButton"));
    }

    @Test
    void addingChecksTheWordInTheBackgroundAndChangingTheWordCancelsTheCheck() throws Exception {
        Hold slow = dictionary.hold("petrichor", false);
        selectTab("addImportTab");
        type("addEnglishField", "petrichor");
        type("addChineseField", "雨后的气味");
        click("addWordButton");
        slow.awaitStarted();

        assertEquals("Checking petrichor in the dictionaries...", text("addWordStatusLabel"));
        assertTrue(isVisible("addWordBusyIndicator"));
        snapshot("checking");
        assertFalse(isDisabled("addWordButton"), "the Add button stays usable");
        type("addNoteArea", "typed while checking");

        type("addEnglishField", "petrichors");

        assertEquals("Canceled adding petrichor: the English word changed.", text("addWordStatusLabel"));
        assertFalse(isVisible("addWordBusyIndicator"));
        Fx.waitUntil("the check is interrupted", () -> dictionary.interrupted.contains("petrichor"));
        waitForBackgroundTasks();
        assertTrue(services.wordRepository().findByEnglish(currentDeck().getId(), "petrichor").isEmpty());
        assertTrue(dialogs.shown().isEmpty(), dialogs.shown().toString());
    }

    @Test
    void whatTheFormHoldsWhenTheCheckFinishesIsAdded() throws Exception {
        dictionary.answer("petrichor", found("petrichor", "雨后泥土的气味"));
        Hold slow = dictionary.hold("petrichor", false);
        selectTab("addImportTab");
        type("addEnglishField", "petrichor");
        type("addChineseField", "雨后的气味");
        click("addWordButton");
        slow.awaitStarted();

        type("addChineseField", "雨后泥土的气味");
        type("addTagsField", "mine");
        slow.release();

        waitForText("addWordStatusLabel", "Added to " + STARTER_DECK + ": petrichor | Verified by " + SlowDictionary.SOURCE);
        WordCard saved = services.wordRepository().findByEnglish(currentDeck().getId(), "petrichor").orElseThrow();
        assertEquals("雨后泥土的气味", saved.getChinese());
        assertEquals("mine; VERIFIED; " + SlowDictionary.SOURCE, saved.getTags());
        assertFalse(isVisible("addWordBusyIndicator"));
    }

    @Test
    void whenTheDictionariesCannotBeAskedTheUserCanRetryAddUncheckedOrCancel() throws Exception {
        DictionaryLookupResult offline = DictionaryLookupResult.unavailable(LookupOutcome.NETWORK_ERROR,
            "dictionaryapi.dev: cannot connect (Connection refused).");
        dictionary.answer("quokka", offline, offline, offline, found("quokka", "短尾矮袋鼠"));
        selectTab("addImportTab");
        type("addEnglishField", "quokka");
        type("addChineseField", "短尾矮袋鼠");

        dialogs.chooseButton("Cancel");
        click("addWordButton");
        waitForText("addWordStatusLabel", "Canceled: quokka");
        ScriptedDialogs.Shown question = dialogs.last(ScriptedDialogs.Kind.CHOOSE);
        assertEquals("Cannot check the word", question.title());
        assertEquals("Cannot check: quokka", question.header());
        assertEquals("Cannot reach the online dictionaries: check the network and try again." + System.lineSeparator()
            + "Not found: the local dictionary does not have this word. | dictionaryapi.dev: cannot connect (Connection refused)."
            + System.lineSeparator() + "Retry, or add it without checking and tag it UNCHECKED?", question.content());
        assertEquals("Retry | Add anyway | Cancel", question.value());
        assertTrue(services.wordRepository().findByEnglish(currentDeck().getId(), "quokka").isEmpty());

        // Still offline at the first retry; the dictionary answers at the second.
        dialogs.chooseButton("Retry").chooseButton("Retry");
        click("addWordButton");
        waitForText("addWordStatusLabel", "Added to " + STARTER_DECK + ": quokka | Verified by " + SlowDictionary.SOURCE);
        assertEquals(3, dialogs.shown(ScriptedDialogs.Kind.CHOOSE).size());
        assertEquals("VERIFIED; " + SlowDictionary.SOURCE,
            services.wordRepository().findByEnglish(currentDeck().getId(), "quokka").orElseThrow().getTags());

        dictionary.answer("wallaby", DictionaryLookupResult.unavailable(LookupOutcome.TIMEOUT, "Wiktionary: no answer within 5 seconds."));
        type("addEnglishField", "wallaby");
        type("addChineseField", "沙袋鼠");
        dialogs.chooseButton("Add anyway");
        click("addWordButton");
        waitForText("addWordStatusLabel",
            "Added to " + STARTER_DECK + ": wallaby | Not checked: the dictionaries could not be asked");
        assertTrue(dialogs.last(ScriptedDialogs.Kind.CHOOSE).content().startsWith("The online dictionary did not answer in time: try again later."));
        assertEquals("UNCHECKED", services.wordRepository().findByEnglish(currentDeck().getId(), "wallaby")
            .orElseThrow().getTags());
        assertFalse(dialogs.wasShown(ScriptedDialogs.Kind.CONFIRM), "only a word no dictionary has is UNVERIFIED");
    }

    private List<String> lookupResults() {
        return Fx.call(() -> {
            @SuppressWarnings("unchecked")
            ListView<DictionaryEntry> list = find("lookupResults", ListView.class);
            return list.getItems().stream().map(DictionaryEntry::english).toList();
        });
    }

    private static DictionaryLookupResult found(String english, String chinese) {
        return DictionaryLookupResult.success("Loaded from the slow dictionary.",
            List.of(new DictionaryEntry(english, chinese, "noun", "", "", SlowDictionary.SOURCE)));
    }

    /** Holds a lookup until the test releases it. */
    static final class Hold {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private final boolean ignoresInterrupts;

        Hold(boolean ignoresInterrupts) {
            this.ignoresInterrupts = ignoresInterrupts;
        }

        void awaitStarted() throws InterruptedException {
            if (!started.await(Fx.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError("The lookup never started");
            }
        }

        void release() {
            released.countDown();
        }

        /** Waits for the release; false when the waiting thread was interrupted first. */
        boolean await() {
            started.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    released.await();
                    break;
                } catch (InterruptedException e) {
                    if (!ignoresInterrupts) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            return true;
        }
    }

    /** Answers from a script, after a hold the test controls, and records interrupted lookups. */
    static final class SlowDictionary implements DictionaryService {
        static final String SOURCE = "Slow dictionary";
        final List<String> interrupted = new CopyOnWriteArrayList<>();
        private final Map<String, Deque<DictionaryLookupResult>> answers = new ConcurrentHashMap<>();
        private final Map<String, Hold> holds = new ConcurrentHashMap<>();
        private final List<Hold> allHolds = new CopyOnWriteArrayList<>();

        /** The answers to the next lookups of {@code word}; the last one stays. */
        void answer(String word, DictionaryLookupResult... results) {
            answers.put(word, new ArrayDeque<>(List.of(results)));
        }

        Hold hold(String word, boolean ignoresInterrupts) {
            Hold hold = new Hold(ignoresInterrupts);
            holds.put(word, hold);
            allHolds.add(hold);
            return hold;
        }

        void releaseAll() {
            allHolds.forEach(Hold::release);
        }

        @Override
        public DictionaryLookupResult lookup(String english) {
            String word = english.trim().toLowerCase(Locale.ROOT);
            Hold hold = holds.remove(word);
            if (hold != null && !hold.await()) {
                interrupted.add(word);
                return DictionaryLookupResult.interrupted();
            }
            Deque<DictionaryLookupResult> queue = answers.get(word);
            if (queue == null) {
                return DictionaryLookupResult.notFound("Not found: the test dictionary does not have this word.");
            }
            synchronized (queue) {
                return queue.size() > 1 ? queue.poll() : queue.peek();
            }
        }

        @Override
        public boolean isConfigured() {
            return true;
        }
    }
}
