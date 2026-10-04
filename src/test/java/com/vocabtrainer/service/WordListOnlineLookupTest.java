package com.vocabtrainer.service;

import com.vocabtrainer.app.AppServices;
import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.DeckRepository;
import com.vocabtrainer.repository.DictionaryCacheRepository;
import com.vocabtrainer.repository.SettingsRepository;
import com.vocabtrainer.repository.TestDatabases;
import com.vocabtrainer.repository.WordRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Filling in the meanings of a word list never reaches the network unless the user allowed online
 * lookups for that import and offline mode is off. Every online dictionary here is a local stub
 * server that records what it is asked.
 */
class WordListOnlineLookupTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-01T09:00:00Z"), ZoneOffset.UTC);
    private static final String PETRICHOR = "[{\"word\":\"petrichor\",\"chinese\":\"雨后泥土的气味\",\"partOfSpeech\":\"noun\"}]";

    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    private StubHttpServer server;
    private SettingsService settings;
    private DictionaryCacheRepository cache;
    private Deck deck;
    private WordRepository words;
    private Path list;

    @BeforeEach
    void setUp() throws Exception {
        server = new StubHttpServer();
        server.answer("/api", 200, PETRICHOR);
        DatabaseManager databaseManager = databases.open(tempDir.resolve("online.db"));
        settings = new SettingsService(new SettingsRepository(databaseManager));
        cache = new DictionaryCacheRepository(databaseManager);
        deck = new DeckRepository(databaseManager).ensureDefaultDeck();
        words = new WordRepository(databaseManager);
        list = tempDir.resolve("list.txt");
        Files.writeString(list, "abate\npetrichor\n", StandardCharsets.UTF_8);
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    /** The app's chain with the configured API and the public dictionaries on the stub server. */
    private DictionaryService stubbedChain(LocalDictionaryService local) {
        return DictionaryServiceFactory.compose(local,
            new HttpDictionaryService(server.uri("/api").toString(), "", StubHttpServer.client()),
            new PublicOnlineDictionaryService(StubHttpServer.client(), server.uri("/dictapi/"), server.uri("/wiki/"),
                Duration.ofSeconds(2)),
            cache, CLOCK, settings::isOfflineMode);
    }

    private ImportExportService service(AtomicInteger chainsBuilt) {
        LocalDictionaryService local = new LocalDictionaryService();
        return new ImportExportService(words, new WordValidationService(), local, () -> {
            chainsBuilt.incrementAndGet();
            return stubbedChain(local);
        }, settings::isOfflineMode);
    }

    @Test
    void byDefaultOnlyTheLocalDictionariesAreAsked() {
        AtomicInteger chainsBuilt = new AtomicInteger();

        ImportResult result = service(chainsBuilt).importWordList(list, deck.getId(), WordListOptions.DETECT,
            progress -> { }, () -> false);

        assertEquals(List.of(), server.paths(), "no request without the user's permission");
        assertEquals(0, chainsBuilt.get(), "the online dictionaries are not even built");
        assertEquals(1, result.importedCount());
        assertEquals(List.of("Line 2 skipped: petrichor is not in the local dictionary, so it has no meaning to import"),
            result.messages());
        assertEquals("the local dictionary", result.dictionary());
    }

    @Test
    void offlineModeOverridesAnImportThatAllowsOnlineLookups() {
        settings.saveOfflineMode(true);
        AtomicInteger chainsBuilt = new AtomicInteger();
        WordListOptions online = new WordListOptions(null, true);

        ImportPreview preview = service(chainsBuilt).previewWordList(list, deck.getId(), online);
        ImportResult result = service(chainsBuilt).importWordList(list, deck.getId(), online, progress -> { },
            () -> false);

        assertEquals(List.of(), server.paths(), "no request in offline mode");
        assertEquals(0, chainsBuilt.get());
        assertEquals("Not in the local dictionary: skipped", preview.rows().get(1).status());
        assertEquals(1, result.importedCount());
        assertTrue(result.messages().get(0).contains("is not in the local dictionary"), result.messages().toString());
    }

    @Test
    void onlineLookupsAllowedWhileOnlineAskTheDictionariesAfterTheLocalOnes() throws Exception {
        AtomicInteger chainsBuilt = new AtomicInteger();
        WordListOptions online = new WordListOptions(null, true);

        ImportPreview preview = service(chainsBuilt).previewWordList(list, deck.getId(), online);
        assertEquals(List.of(), server.paths(), "a preview never asks online");
        assertEquals("Not in the local dictionary: looked up online", preview.rows().get(1).status());

        ImportResult result = service(chainsBuilt).importWordList(list, deck.getId(), online, progress -> { },
            () -> false);

        assertEquals(List.of("/api"), server.paths(), "abate is a starter word; only petrichor is asked");
        assertEquals(2, result.importedCount(), result.toSummary());
        assertEquals("the local and online dictionaries", result.dictionary());
        assertEquals("雨后泥土的气味", words.findByEnglish(deck.getId(), "petrichor").orElseThrow().getChinese());
    }

    @Test
    void theAppsImportFollowsTheSavedOfflineModeAndTheUsersChoice() throws Exception {
        try (AppServices services = AppServices.builder(tempDir.resolve("app.db"))
            .clock(CLOCK)
            .dictionaryService((appCache, local) -> DictionaryServiceFactory.compose(local,
                new HttpDictionaryService(server.uri("/api").toString(), "", StubHttpServer.client()),
                new PublicOnlineDictionaryService(StubHttpServer.client(), server.uri("/dictapi/"),
                    server.uri("/wiki/"), Duration.ofSeconds(2)),
                appCache, CLOCK))
            .open()) {
            databases.track(services.databaseManager());
            long deckId = services.startupDeck().getId();
            Path petrichor = tempDir.resolve("petrichor.txt");
            Files.writeString(petrichor, "petrichor\n", StandardCharsets.UTF_8);

            services.settingsService().saveOfflineMode(true);
            services.importExportService().importWordList(petrichor, deckId, new WordListOptions(null, true),
                progress -> { }, () -> false);
            services.settingsService().saveOfflineMode(false);
            services.importExportService().importWordList(petrichor, deckId, WordListOptions.DETECT,
                progress -> { }, () -> false);
            assertEquals(List.of(), server.paths(), "offline, or online lookups not allowed: no request");

            ImportResult allowed = services.importExportService().importWordList(petrichor, deckId,
                new WordListOptions(null, true), progress -> { }, () -> false);

            assertEquals(List.of("/api"), server.paths());
            assertEquals(1, allowed.importedCount(), allowed.toSummary());
        }
    }
}
