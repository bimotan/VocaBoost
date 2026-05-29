package com.vocabtrainer.service;

import com.vocabtrainer.domain.DictionaryEntry;
import com.vocabtrainer.domain.DictionaryLookupResult;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.DictionaryCacheRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DictionaryServiceTest {
    @TempDir
    Path tempDir;

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
        assertTrue(result.message().contains("词条未找到"));
    }

    @Test
    void localDictionaryVerifiesBundledStarterWords() {
        DictionaryService service = new LocalDictionaryService();

        assertTrue(service.verify("abate").found());
        assertFalse(service.verify("notarealword").found());
    }

    @Test
    void cachingDictionaryAvoidsRepeatedDelegateCalls() throws Exception {
        DatabaseManager databaseManager = new DatabaseManager(tempDir.resolve("dictionary.db"));
        databaseManager.initialize();
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
            new DictionaryCacheRepository(databaseManager)
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
            "lucid,ˈluːsɪd,clear,清晰的,adjective,zk"
        ), StandardCharsets.UTF_8);

        DictionaryLookupResult result = new LocalDictionaryService(csv.toString()).lookup("lucid");

        assertTrue(result.success());
        assertEquals("清晰的", result.entries().get(0).chinese());
        assertEquals("ˈluːsɪd", result.entries().get(0).phonetic());
    }

    @Test
    void cacheRefreshCallsDelegateAgain() throws Exception {
        DatabaseManager databaseManager = new DatabaseManager(tempDir.resolve("refresh.db"));
        databaseManager.initialize();
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
        DictionaryService service = new CachingDictionaryService(delegate, new DictionaryCacheRepository(databaseManager));

        service.lookup("abate");
        DictionaryLookupResult refreshed = service.refresh("abate");

        assertEquals(2, calls.get());
        assertEquals("释义2", refreshed.entries().get(0).chinese());
    }

    @Test
    void cachedOnlineDefinitionPlaceholderIsConvertedToDefinition() throws Exception {
        DatabaseManager databaseManager = new DatabaseManager(tempDir.resolve("old-cache.db"));
        databaseManager.initialize();
        DictionaryCacheRepository cacheRepository = new DictionaryCacheRepository(databaseManager);
        String oldPayload = String.join("\t",
            b64("like"),
            b64("请填写中文释义（English definition: Something that a person likes.）"),
            b64("noun"),
            b64("/laɪk/"),
            b64("Tell me your likes and dislikes."),
            b64("dictionaryapi.dev")
        );
        cacheRepository.save("like", oldPayload, "dictionary", LocalDateTime.now());
        DictionaryService service = new CachingDictionaryService(new MockDictionaryService(), cacheRepository);

        DictionaryLookupResult result = service.lookup("like");

        assertTrue(result.success());
        assertEquals("", result.entries().get(0).chinese());
        assertEquals("Something that a person likes.", result.entries().get(0).definition());
    }

    @Test
    void staleMockFallbackCacheIsIgnoredAndReplaced() throws Exception {
        DatabaseManager databaseManager = new DatabaseManager(tempDir.resolve("stale-mock-cache.db"));
        databaseManager.initialize();
        DictionaryCacheRepository cacheRepository = new DictionaryCacheRepository(databaseManager);
        String stalePayload = String.join("\t",
            b64("hi"),
            b64("请手动填写中文释义"),
            b64(""),
            b64(""),
            b64(""),
            b64("Mock fallback")
        );
        cacheRepository.save("hi", stalePayload, "dictionary", LocalDateTime.now());
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
        DictionaryService service = new CachingDictionaryService(delegate, cacheRepository);

        DictionaryLookupResult result = service.lookup("hi");

        assertEquals(1, calls.get());
        assertEquals("test", result.entries().get(0).source());
        assertEquals("used as a greeting", result.entries().get(0).definition());
    }

    private String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
