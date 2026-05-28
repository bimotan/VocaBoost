package com.vocabtrainer.service;

import com.vocabtrainer.domain.DictionaryEntry;
import com.vocabtrainer.domain.DictionaryLookupResult;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.DictionaryCacheRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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
}
