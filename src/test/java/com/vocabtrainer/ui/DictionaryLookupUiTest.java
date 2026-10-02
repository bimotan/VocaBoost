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
        assertTrue(text("lookupStatusLabel").startsWith("词条未找到。"), text("lookupStatusLabel"));
    }

    @Test
    void theLookupBoxTellsAMissingWordFromADictionaryThatCannotBeAskedAndOffersRetry() {
        dictionary.answer("quokka",
            DictionaryLookupResult.unavailable(LookupOutcome.RATE_LIMITED, "dictionaryapi.dev：查询次数受限（HTTP 429），请稍后再试。"),
            found("quokka", "短尾矮袋鼠"));
        selectTab("addImportTab");

        type("lookupField", "snarkle");
        click("lookupButton");
        waitForBackgroundTasks();
        assertTrue(text("lookupStatusLabel").startsWith("词条未找到。"), text("lookupStatusLabel"));
        assertFalse(isVisible("lookupRetryButton"));

        type("lookupField", "quokka");
        click("lookupButton");
        waitForBackgroundTasks();
        assertEquals("在线词典暂时限制了查询次数，请稍后重试。" + System.lineSeparator()
            + "词条未找到：本地词库没有该词条。 | dictionaryapi.dev：查询次数受限（HTTP 429），请稍后再试。",
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
            "dictionaryapi.dev：无法连接（Connection refused）。");
        dictionary.answer("quokka", offline, offline, offline, found("quokka", "短尾矮袋鼠"));
        selectTab("addImportTab");
        type("addEnglishField", "quokka");
        type("addChineseField", "短尾矮袋鼠");

        dialogs.chooseButton("取消");
        click("addWordButton");
        waitForText("addWordStatusLabel", "Canceled: quokka");
        ScriptedDialogs.Shown question = dialogs.last(ScriptedDialogs.Kind.CHOOSE);
        assertEquals("无法验证", question.title());
        assertEquals("无法验证：quokka", question.header());
        assertEquals("无法连接在线词典，请检查网络后重试。" + System.lineSeparator()
            + "词条未找到：本地词库没有该词条。 | dictionaryapi.dev：无法连接（Connection refused）。" + System.lineSeparator()
            + "重试，或不经验证直接添加并标记为 UNCHECKED？", question.content());
        assertEquals("重试 | 直接添加 | 取消", question.value());
        assertTrue(services.wordRepository().findByEnglish(currentDeck().getId(), "quokka").isEmpty());

        // Still offline at the first retry; the dictionary answers at the second.
        dialogs.chooseButton("重试").chooseButton("重试");
        click("addWordButton");
        waitForText("addWordStatusLabel", "Added to " + STARTER_DECK + ": quokka | Verified by " + SlowDictionary.SOURCE);
        assertEquals(3, dialogs.shown(ScriptedDialogs.Kind.CHOOSE).size());
        assertEquals("VERIFIED; " + SlowDictionary.SOURCE,
            services.wordRepository().findByEnglish(currentDeck().getId(), "quokka").orElseThrow().getTags());

        dictionary.answer("wallaby", DictionaryLookupResult.unavailable(LookupOutcome.TIMEOUT, "Wiktionary：5 秒内没有响应。"));
        type("addEnglishField", "wallaby");
        type("addChineseField", "沙袋鼠");
        dialogs.chooseButton("直接添加");
        click("addWordButton");
        waitForText("addWordStatusLabel",
            "Added to " + STARTER_DECK + ": wallaby | Not checked: the dictionaries could not be asked");
        assertTrue(dialogs.last(ScriptedDialogs.Kind.CHOOSE).content().startsWith("在线词典响应超时，请稍后重试。"));
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
                return DictionaryLookupResult.notFound("词条未找到：测试词典没有该词条。");
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
