package com.vocabtrainer.service;

import com.vocabtrainer.domain.DictionaryEntry;
import com.vocabtrainer.domain.LookupOutcome;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.DictionaryCacheRepository;
import com.vocabtrainer.repository.TestDatabases;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A word's synonyms, antonyms and recording (review finding G2): read from the lookup cache without
 * a request, looked up and downloaded only when asked, never in offline mode.
 */
class WordExtrasServiceTest {
    private static final String ABATE = """
        [{"word":"abate","phonetic":"/əˈbeɪt/","phonetics":[{"audio":"%s"}],
          "meanings":[{"partOfSpeech":"verb","synonyms":["subside"],"antonyms":["intensify"],
            "definitions":[{"definition":"To lessen in force.","synonyms":["wane"]}]}]}]
        """;

    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    private final AtomicBoolean offline = new AtomicBoolean();
    private StubHttpServer server;
    private DictionaryCacheRepository cache;
    private WordExtrasService extras;

    @BeforeEach
    void setUp() throws Exception {
        server = new StubHttpServer();
        DatabaseManager databaseManager = databases.open(tempDir.resolve("extras.db"));
        cache = new DictionaryCacheRepository(databaseManager);
        DictionaryService online = new CachingDictionaryService(new OfflineAwareDictionaryService(
            new PublicOnlineDictionaryService(StubHttpServer.client(), server.uri("/dictapi/"), server.uri("/wiki/"),
                Duration.ofSeconds(2)), offline::get, "Online dictionaries"), cache, Clock.systemDefaultZone());
        extras = new WordExtrasService(cache, online, offline::get, StubHttpServer.client());
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void lookingAWordUpKeepsItsExtrasInTheCacheWhichAnswersWithoutARequest() {
        assertEquals(WordExtras.NONE, extras.cached("abate"));
        server.answer("/dictapi/abate", 200, ABATE.formatted(server.uri("/media/abate-us.mp3")));

        WordExtrasService.Lookup lookup = extras.lookUp("abate");

        WordExtras expected = new WordExtras(List.of("wane", "subside"), List.of("intensify"),
            server.uri("/media/abate-us.mp3").toString());
        assertEquals(LookupOutcome.FOUND, lookup.outcome());
        assertEquals(expected, lookup.extras());
        int requests = server.requests().size();
        assertEquals(expected, extras.cached("Abate"), "read back from dictionary_cache");
        assertEquals(requests, server.requests().size(), "reading the cache sends nothing");
    }

    @Test
    void anEntryCachedWithoutExtrasIsLookedUpAgainWhenAsked() throws Exception {
        cache.save("abate", DictionaryCachePayload.serialize(List.of(new DictionaryEntry("abate", "", "verb", "", "",
            "dictionaryapi.dev", "To lessen."))), "dictionaryapi.dev", java.time.LocalDateTime.now());
        server.answer("/dictapi/abate", 200, ABATE.formatted(server.uri("/media/abate-us.mp3")));

        assertEquals(WordExtras.NONE, extras.cached("abate"));
        assertEquals(List.of("wane", "subside"), extras.lookUp("abate").extras().synonyms());
        assertTrue(server.paths().contains("/dictapi/abate"), "asked again although a fresh entry was cached");
    }

    @Test
    void offlineNothingIsLookedUpOrDownloaded() throws Exception {
        offline.set(true);

        WordExtrasService.Lookup lookup = extras.lookUp("abate");
        IllegalStateException refused = assertThrows(IllegalStateException.class,
            () -> extras.audioFile(server.uri("/media/abate-us.mp3").toString()));

        assertEquals(LookupOutcome.OFFLINE, lookup.outcome());
        assertEquals("offline mode is on, so it is not downloaded", refused.getMessage());
        assertEquals(List.of(), server.paths());
        assertFalse(extras.canLookUp());
    }

    @Test
    void aRecordingIsDownloadedOnceAndBadOnesAreRefused() throws Exception {
        server.answer("/media/abate-us.mp3", 200, "ID3-fake-mp3-bytes");
        String url = server.uri("/media/abate-us.mp3").toString();

        Path file = extras.audioFile(url);
        Path again = extras.audioFile(url);

        assertEquals("ID3-fake-mp3-bytes", Files.readString(file));
        assertTrue(file.getFileName().toString().endsWith(".mp3"), "the media player tells the format by it");
        assertEquals(file, again);
        assertEquals(1, server.paths().size(), "downloaded once");

        assertThrows(IllegalStateException.class, () -> extras.audioFile(server.uri("/media/missing.mp3").toString()));
        assertThrows(IllegalStateException.class, () -> extras.audioFile("file:///etc/passwd"));
        server.answer("/media/huge.mp3", 200, "x".repeat(WordExtrasService.MAX_AUDIO_BYTES + 1));
        assertThrows(IllegalStateException.class, () -> extras.audioFile(server.uri("/media/huge.mp3").toString()));
    }

    @Test
    void theExtrasAreOnlyThoseOfTheWordItself() {
        List<DictionaryEntry> entries = List.of(
            new DictionaryEntry("abandon", "", "verb", "", "", "dictionaryapi.dev", "", "", List.of("desert"),
                List.of(), "https://example.com/abandon.mp3"),
            new DictionaryEntry("abandons", "", "verb", "", "", "dictionaryapi.dev", "", "",
                List.of("Abandons", "quits", "quits"), List.of(), ""));

        assertEquals(new WordExtras(List.of("quits"), List.of(), ""), WordExtras.of("abandons", entries),
            "not the base form's, not the word itself, each once");
        assertTrue(WordExtras.of("lucid", entries).isEmpty());
    }
}
