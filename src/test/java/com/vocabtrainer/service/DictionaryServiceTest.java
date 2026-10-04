package com.vocabtrainer.service;

import com.vocabtrainer.TestClock;
import com.vocabtrainer.domain.DictionaryEntry;
import com.vocabtrainer.domain.DictionaryLookupResult;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.DictionaryCacheRepository;
import com.vocabtrainer.repository.EcdictRepository;
import com.vocabtrainer.repository.TestDatabases;
import com.vocabtrainer.service.ecdict.EcdictFixtures;
import com.vocabtrainer.service.ecdict.EcdictImportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DictionaryServiceTest {
    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    /** The time the cache entries are saved and read at. */
    private final TestClock clock = new TestClock(LocalDateTime.of(2026, 10, 1, 9, 0));

    @Test
    void mockDictionaryReturnsCandidate() {
        DictionaryLookupResult result = new MockDictionaryService().lookup("abate");

        assertTrue(result.success());
        assertEquals("abate", result.entries().get(0).english());
        assertFalse(result.entries().get(0).chinese().isBlank());
    }

    @Test
    void mockDictionaryReportsMissingUnknownWords() {
        DictionaryLookupResult result = new MockDictionaryService().lookup("notarealword");

        assertFalse(result.success());
        assertTrue(result.message().contains("Not found"));
    }

    @Test
    void localDictionaryVerifiesBundledStarterWords() {
        DictionaryService service = new LocalDictionaryService();

        assertTrue(service.verify("abate").found());
        assertFalse(service.verify("notarealword").found());
    }

    @Test
    void cachingDictionaryAvoidsRepeatedDelegateCalls() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("dictionary.db"));
        AtomicInteger calls = new AtomicInteger();
        DictionaryService delegate = new DictionaryService() {
            @Override
            public DictionaryLookupResult lookup(String english) {
                calls.incrementAndGet();
                return DictionaryLookupResult.success("ok", List.of(new DictionaryEntry(
                    english,
                    "清晰的",
                    "adjective",
                    "",
                    "",
                    "test"
                )));
            }

            @Override
            public boolean isConfigured() {
                return true;
            }
        };
        DictionaryService service = new CachingDictionaryService(
            delegate,
            new DictionaryCacheRepository(databaseManager),
            clock
        );

        service.lookup("lucid");
        service.lookup("LUCID");

        assertEquals(1, calls.get());
    }

    @Test
    void localDictionaryReadsEcdictStyleHeaders() throws Exception {
        Path csv = tempDir.resolve("ecdict.csv");
        Files.writeString(csv, String.join(System.lineSeparator(),
            "word,phonetic,definition,translation,pos,tag",
            "lucidx,ˈluːsɪd,clear,清晰的,adjective,zk"
        ), StandardCharsets.UTF_8);
        try (EcdictRepository ecdict = new EcdictRepository(tempDir.resolve("ecdict.db"))) {
            new EcdictImportService(ecdict).importCsv(csv, progress -> { }, () -> false);

            DictionaryLookupResult result = new LocalDictionaryService(ecdict).lookup("lucidx");

            assertTrue(result.success());
            assertEquals("清晰的", result.entries().get(0).chinese());
            assertEquals("ˈluːsɪd", result.entries().get(0).phonetic());
            assertEquals("clear", result.entries().get(0).definition());
        }
    }

    @Test
    void theImportedDictionaryAnswersBeforeTheCacheSoAStaleRawEntryCannotWin() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("vocab.db"));
        DictionaryCacheRepository cache = new DictionaryCacheRepository(databaseManager);
        // What earlier versions cached for ECDICT words: the raw translation.
        cache.save("abandon", String.join("\t", b64("abandon"), b64("vt. 放弃, 抛弃\\nn. 放任"), b64(""), b64(""), b64(""),
            b64("ECDICT/local CSV"), b64("")), "dictionary", clock.now());
        Path csv = EcdictFixtures.write(tempDir.resolve("ecdict.csv"), false, List.of(EcdictFixtures.ABANDON));
        try (EcdictRepository ecdict = new EcdictRepository(tempDir.resolve("ecdict.db"))) {
            new EcdictImportService(ecdict).importCsv(csv, progress -> { }, () -> false);
            DictionaryService service = DictionaryServiceFactory.create(cache, new LocalDictionaryService(ecdict),
                Map.of(), clock);

            DictionaryLookupResult result = service.lookup("abandon");

            assertEquals("Loaded from local dictionary.", result.message());
            assertEquals("放弃; 抛弃; 遗弃; 使屈从; 沉溺; 放纵; 放任; 无拘束; 狂热", result.entries().get(0).chinese());
            assertEquals(result, service.refresh("abandon"));
        }
    }

    @Test
    void aRawEntryAnEarlierVersionCachedFromEcdictIsNotServedWhileNothingIsImported() throws Exception {
        // During the first import after an upgrade, or after the ECDICT path was cleared.
        DatabaseManager databaseManager = databases.open(tempDir.resolve("leftover.db"));
        DictionaryCacheRepository cache = new DictionaryCacheRepository(databaseManager);
        cache.save("abandon", String.join("\t", b64("abandon"), b64("vt. 放弃, 抛弃\\nn. 放任"), b64(""), b64(""), b64(""),
            b64("ECDICT/local CSV"), b64("")), "dictionary", clock.now());
        DictionaryService online = new DictionaryService() {
            @Override
            public DictionaryLookupResult lookup(String english) {
                return DictionaryLookupResult.success("online", List.of(new DictionaryEntry(
                    english, "在线释义", "", "", "", "test")));
            }

            @Override
            public boolean isConfigured() {
                return true;
            }
        };
        try (EcdictRepository ecdict = new EcdictRepository(tempDir.resolve("not-imported.db"))) {
            DictionaryService service = new CompositeDictionaryService(List.of(new LocalDictionaryService(ecdict),
                new CachingDictionaryService(online, cache, clock)));

            DictionaryLookupResult result = service.lookup("abandon");

            assertEquals("online", result.message());
            assertEquals("在线释义", result.entries().get(0).chinese());
            assertEquals("Loaded from dictionary cache.", service.lookup("abandon").message());
            assertEquals("在线释义", service.lookup("abandon").entries().get(0).chinese());
        }
    }

    @Test
    void compositeRefreshAsksEachDictionaryToRefresh() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("composite.db"));
        AtomicInteger calls = new AtomicInteger();
        DictionaryService online = new DictionaryService() {
            @Override
            public DictionaryLookupResult lookup(String english) {
                return DictionaryLookupResult.success("ok", List.of(new DictionaryEntry(
                    english, "释义" + calls.incrementAndGet(), "", "", "", "test")));
            }

            @Override
            public boolean isConfigured() {
                return true;
            }
        };
        DictionaryService service = new CompositeDictionaryService(List.of(new LocalDictionaryService(),
            new CachingDictionaryService(online, new DictionaryCacheRepository(databaseManager), clock)));

        assertEquals("释义1", service.lookup("petrichor").entries().get(0).chinese());
        assertEquals("释义1", service.lookup("petrichor").entries().get(0).chinese());
        assertEquals("释义2", service.refresh("petrichor").entries().get(0).chinese());
    }

    @Test
    void cacheRefreshCallsDelegateAgain() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("refresh.db"));
        AtomicInteger calls = new AtomicInteger();
        DictionaryService delegate = new DictionaryService() {
            @Override
            public DictionaryLookupResult lookup(String english) {
                int call = calls.incrementAndGet();
                return DictionaryLookupResult.success("ok", List.of(new DictionaryEntry(
                    english,
                    "释义" + call,
                    "",
                    "",
                    "",
                    "test"
                )));
            }

            @Override
            public boolean isConfigured() {
                return true;
            }
        };
        DictionaryService service = new CachingDictionaryService(delegate, new DictionaryCacheRepository(databaseManager),
            clock);

        service.lookup("abate");
        DictionaryLookupResult refreshed = service.refresh("abate");

        assertEquals(2, calls.get());
        assertEquals("释义2", refreshed.entries().get(0).chinese());
    }

    @Test
    void cachedOnlineDefinitionPlaceholderIsConvertedToDefinition() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("old-cache.db"));
        DictionaryCacheRepository cacheRepository = new DictionaryCacheRepository(databaseManager);
        String oldPayload = String.join("\t",
            b64("like"),
            b64("请填写中文释义（English definition: Something that a person likes.）"),
            b64("noun"),
            b64("/laɪk/"),
            b64("Tell me your likes and dislikes."),
            b64("dictionaryapi.dev")
        );
        cacheRepository.save("like", oldPayload, "dictionary", clock.now());
        DictionaryService service = new CachingDictionaryService(new MockDictionaryService(), cacheRepository, clock);

        DictionaryLookupResult result = service.lookup("like");

        assertTrue(result.success());
        assertEquals("", result.entries().get(0).chinese());
        assertEquals("Something that a person likes.", result.entries().get(0).definition());
    }

    @Test
    void staleMockFallbackCacheIsIgnoredAndReplaced() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("stale-mock-cache.db"));
        DictionaryCacheRepository cacheRepository = new DictionaryCacheRepository(databaseManager);
        String stalePayload = String.join("\t",
            b64("hi"),
            b64("请手动填写中文释义"),
            b64(""),
            b64(""),
            b64(""),
            b64("Mock fallback")
        );
        cacheRepository.save("hi", stalePayload, "dictionary", clock.now());
        AtomicInteger calls = new AtomicInteger();
        DictionaryService delegate = new DictionaryService() {
            @Override
            public DictionaryLookupResult lookup(String english) {
                calls.incrementAndGet();
                return DictionaryLookupResult.success("fresh", List.of(new DictionaryEntry(
                    english,
                    "",
                    "interjection",
                    "/haɪ/",
                    "Hi, how are you?",
                    "test",
                    "used as a greeting"
                )));
            }

            @Override
            public boolean isConfigured() {
                return true;
            }
        };
        DictionaryService service = new CachingDictionaryService(delegate, cacheRepository, clock);

        DictionaryLookupResult result = service.lookup("hi");

        assertEquals(1, calls.get());
        assertEquals("test", result.entries().get(0).source());
        assertEquals("used as a greeting", result.entries().get(0).definition());
    }

    private String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
