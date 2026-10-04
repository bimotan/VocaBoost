package com.vocabtrainer.ui;

import com.sun.net.httpserver.HttpServer;
import com.vocabtrainer.app.AppServices;
import com.vocabtrainer.domain.DictionaryEntry;
import com.vocabtrainer.domain.DictionaryLookupResult;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.service.CachingDictionaryService;
import com.vocabtrainer.service.DictionaryService;
import com.vocabtrainer.service.WordExtrasService;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A word's synonyms, antonyms and recording in its details card (review finding G2), from a fake
 * online dictionary and a local server for the recording, so nothing reaches the network.
 */
@Tag("ui")
class WordExtrasUiTest extends MainWindowUiTest {
    private static final byte[] RECORDING = "ID3 fake mp3".getBytes(StandardCharsets.UTF_8);

    private final FakeOnlineDictionary online = new FakeOnlineDictionary();
    private HttpServer audioServer;

    @Override
    AppServices.Builder configure(AppServices.Builder builder) {
        try {
            audioServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        audioServer.createContext("/", exchange -> {
            try (exchange; OutputStream out = exchange.getResponseBody()) {
                exchange.sendResponseHeaders(200, RECORDING.length);
                out.write(RECORDING);
            }
        });
        audioServer.start();
        HttpClient direct = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
        return builder.wordExtras((cache, settings) ->
            new WordExtrasService(cache, new CachingDictionaryService(online, cache), settings::isOfflineMode, direct));
    }

    @AfterEach
    void stopAudioServer() {
        audioServer.stop(0);
    }

    @Test
    void theAnsweredWordShowsItsSynonymsAndPlaysItsRecording() throws Exception {
        cacheExtrasOf("abate");
        answerAbate();

        assertEquals("subside, wane", text("reviewDetailsSynonyms"));
        assertEquals("intensify", text("reviewDetailsAntonyms"));
        assertTrue(isVisible("reviewDetailsPlayButton"));
        assertFalse(isVisible("reviewDetailsExtrasLink"), "nothing to look up");
        assertEquals(List.of(), online.asked, "shown from the cache");
        snapshot("answered");

        click("reviewDetailsPlayButton");
        waitForBackgroundTasks();
        assertEquals(1, audio.played.size());
        assertTrue(java.util.Arrays.equals(RECORDING, Files.readAllBytes(audio.played.get(0))));

        audio.failure = "MEDIA_UNSUPPORTED";
        click("reviewDetailsPlayButton");
        waitForBackgroundTasks();
        assertEquals("Cannot play the recording: MEDIA_UNSUPPORTED", text("reviewDetailsExtrasStatus"));

        click("offlineModeToggle");
        assertTrue(isDisabled("reviewDetailsPlayButton"), "offline mode downloads nothing");
        assertEquals("subside, wane", text("reviewDetailsSynonyms"), "what is cached is still shown");
    }

    @Test
    void synonymsAreNeverShownBeforeTheAnswer() throws Exception {
        cacheExtrasOf("abate");
        selectTab("reviewTab");
        click("resetSessionButton");
        selectMode(com.vocabtrainer.domain.ReviewMode.ZH_TO_EN);

        assertFalse(isVisible("reviewDetailsCard"));
        assertFalse(isVisible("reviewDetailsSynonyms"));
    }

    @Test
    void aWordWithNothingKnownCanBeLookedUpForItsExtras() throws Exception {
        online.entries.put("abate", extrasEntry("abate"));
        answerAbate();
        assertTrue(isVisible("reviewDetailsExtrasLink"));
        assertFalse(isVisible("reviewDetailsSynonyms"));
        assertFalse(isVisible("reviewDetailsPlayButton"));

        click("reviewDetailsExtrasLink");
        waitForBackgroundTasks();

        assertEquals(List.of("abate"), online.asked);
        assertEquals("subside, wane", text("reviewDetailsSynonyms"));
        assertTrue(isVisible("reviewDetailsPlayButton"));
        assertFalse(isVisible("reviewDetailsExtrasLink"));
        assertTrue(services.wordExtras().cached("abate").hasAudio(), "kept in the lookup cache");
    }

    @Test
    void offlineNoLookupIsOffered() throws Exception {
        click("offlineModeToggle");
        answerAbate();

        assertFalse(isVisible("reviewDetailsExtrasLink"));
        assertEquals(List.of(), online.asked);
    }

    @Test
    void aWordTheDictionaryLacksSaysSo() throws Exception {
        answerAbate();
        click("reviewDetailsExtrasLink");
        waitForBackgroundTasks();

        assertEquals("The online dictionaries do not have abate.", text("reviewDetailsExtrasStatus"));
        assertFalse(isVisible("reviewDetailsExtrasLink"), "asked once");
    }

    @Test
    void theWordListHasPhoneticAndPartOfSpeechColumnsAndTheSelectedWordsExtras() throws Exception {
        cacheExtrasOf("abate");
        WordCard lucid = services.wordRepository().findByEnglish(currentDeck().getId(), "lucid").orElseThrow();
        lucid.setPhonetic("/ˈluːsɪd/");
        services.wordRepository().update(lucid);
        selectTab("wordListTab");
        click("refreshWordsButton");

        List<String> headers = Fx.call(() -> this.<WordCard>table("wordTable").getColumns().stream()
            .map(TableColumn::getText).toList());
        assertEquals(List.of("English", "Chinese", "Next review", "Interval", "Memory", "Status", "Phonetic", "POS",
            "Deck"), headers);
        assertTrue(Fx.call(() -> this.<WordCard>table("wordTable").isTableMenuButtonVisible()),
            "columns can be hidden");
        assertEquals("/ˈluːsɪd/", cell("wordTable", "lucid", 6));
        assertEquals("adjective", cell("wordTable", "lucid", 7));
        Fx.run(() -> {
            TableView<WordCard> table = table("wordTable");
            TableColumn<WordCard, ?> pos = table.getColumns().get(7);
            table.getSortOrder().setAll(List.of(pos));
        });
        assertEquals("adjective", Fx.call(() -> this.<WordCard>table("wordTable").getItems().get(0).getPartOfSpeech()),
            "sorted by part of speech");

        selectWord("abate");
        assertEquals("subside, wane", text("wordDetailsSynonyms"));
        assertTrue(isVisible("wordDetailsPlayButton"));
        snapshot("word-list");

        // A lookup still running for a word the user left is dropped.
        online.entries.put("lucid", extrasEntry("lucid"));
        online.hold = new CountDownLatch(1);
        selectWord("lucid");
        click("wordDetailsExtrasLink");
        selectWord("abate");
        online.hold.countDown();
        waitForBackgroundTasks();

        assertEquals("subside, wane", text("wordDetailsSynonyms"));
        assertEquals("", text("wordDetailsExtrasStatus"));
        selectWord("lucid");
        assertEquals("subside, wane", text("wordDetailsSynonyms"), "lucid's own lookup finished and was cached");
    }

    private void answerAbate() {
        selectTab("reviewTab");
        click("resetSessionButton");
        assertEquals("abate", text("reviewWordLabel"));
        type("answerField", "减弱");
        click("submitAnswerButton");
        waitForBackgroundTasks();
    }

    private void selectMode(com.vocabtrainer.domain.ReviewMode mode) {
        this.<com.vocabtrainer.domain.ReviewMode>select("reviewModeSelector", item -> item == mode);
    }

    private void selectWord(String english) {
        Fx.run(() -> {
            TableView<WordCard> table = table("wordTable");
            WordCard word = table.getItems().stream()
                .filter(item -> item.getEnglish().equals(english))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No row " + english));
            table.getSelectionModel().clearSelection();
            table.getSelectionModel().select(word);
        });
    }

    /** Puts what the fake dictionary has for {@code english} into the lookup cache, as a lookup would. */
    private void cacheExtrasOf(String english) {
        online.entries.put(english, extrasEntry(english));
        new CachingDictionaryService(online, services.dictionaryCacheRepository()).lookup(english);
        online.asked.clear();
    }

    private DictionaryEntry extrasEntry(String english) {
        return new DictionaryEntry(english, "", "verb", "/əˈbeɪt/", "", "dictionaryapi.dev", "To lessen.", "",
            List.of("subside", "wane"), List.of("intensify"),
            "http://127.0.0.1:" + audioServer.getAddress().getPort() + "/" + english + "-us.mp3");
    }

    /** Answers from {@link #entries}; a word it lacks is not found. */
    static final class FakeOnlineDictionary implements DictionaryService {
        final Map<String, DictionaryEntry> entries = new ConcurrentHashMap<>();
        final List<String> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile CountDownLatch hold;

        @Override
        public DictionaryLookupResult lookup(String english) {
            CountDownLatch waitFor = hold;
            if (waitFor != null) {
                try {
                    waitFor.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return DictionaryLookupResult.interrupted();
                }
            }
            asked.add(english);
            DictionaryEntry entry = entries.get(english);
            return entry == null ? DictionaryLookupResult.notFound("Not found: dictionaryapi.dev does not have this word.")
                : DictionaryLookupResult.success("Loaded from dictionaryapi.dev.", List.of(entry));
        }

        @Override
        public boolean isConfigured() {
            return true;
        }
    }
}
