package com.vocabtrainer.service;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.DictionaryEntry;
import com.vocabtrainer.domain.DictionaryLookupResult;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.AchievementRepository;
import com.vocabtrainer.repository.AiCacheRepository;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.DeckRepository;
import com.vocabtrainer.repository.DictionaryCacheRepository;
import com.vocabtrainer.repository.EcdictRepository;
import com.vocabtrainer.repository.GoalRepository;
import com.vocabtrainer.repository.ReviewLogRepository;
import com.vocabtrainer.repository.TestDatabases;
import com.vocabtrainer.repository.WordRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fallbacks that keep the app running must still leave the reason in the log. */
class SwallowedErrorLoggingTest {
    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    @Test
    void localDictionaryLogsWhyTheImportedDictionaryCannotBeReadAndStillAnswers() throws Exception {
        Path broken = tempDir.resolve("ecdict.db");
        Files.writeString(broken, "not a SQLite database, for example a damaged file");

        DictionaryLookupResult result;
        LocalDictionaryStatus status;
        List<LogRecord> warnings;
        try (EcdictRepository ecdict = new EcdictRepository(broken);
             LogCapture log = LogCapture.of(LocalDictionaryService.class)) {
            LocalDictionaryService service = new LocalDictionaryService(ecdict);
            result = service.lookup("abate");
            status = service.status();
            warnings = log.warnings();
        }

        assertTrue(result.success(), "the bundled starter words still answer");
        assertFalse(status.ecdictImported());
        assertEquals(2, warnings.size(), "lookup and status");
        assertTrue(warnings.get(0).getMessage().contains("'abate'"), warnings.get(0).getMessage());
        assertTrue(warnings.get(1).getMessage().contains(broken.toAbsolutePath().toString()), warnings.get(1).getMessage());
        assertTrue(warnings.stream().allMatch(record -> record.getThrown() instanceof SQLException));
    }

    @Test
    void cachingAiServiceLogsCacheFailuresAndStillAnswers() {
        // Not initialized, so the ai_cache table does not exist and every cache call fails.
        DatabaseManager brokenDatabase = databases.track(new DatabaseManager(tempDir.resolve("no-tables.db")));
        CachingAiService service = new CachingAiService(availableAi("explanation"), new AiCacheRepository(brokenDatabase),
            "test-provider");

        String response;
        List<LogRecord> warnings;
        try (LogCapture log = LogCapture.of(CachingAiService.class)) {
            response = service.explain(WordCard.createNew(1, "lucid", "清晰的"));
            warnings = log.warnings();
        }

        assertEquals("explanation", response);
        assertEquals(2, warnings.size(), "cache read and cache write failures");
        assertTrue(warnings.stream().allMatch(record -> record.getThrown() instanceof SQLException));
    }

    @Test
    void cachingDictionaryServiceLogsCacheFailuresAndStillLooksUp() {
        DatabaseManager brokenDatabase = databases.track(new DatabaseManager(tempDir.resolve("no-tables.db")));
        DictionaryService delegate = new DictionaryService() {
            @Override
            public DictionaryLookupResult lookup(String english) {
                return DictionaryLookupResult.success("ok", List.of(
                    new DictionaryEntry(english, "清晰的", "adj", "", "", "test")));
            }

            @Override
            public boolean isConfigured() {
                return true;
            }
        };
        CachingDictionaryService service = new CachingDictionaryService(delegate,
            new DictionaryCacheRepository(brokenDatabase));

        DictionaryLookupResult result;
        DictionaryLookupResult refreshed;
        List<LogRecord> warnings;
        try (LogCapture log = LogCapture.of(CachingDictionaryService.class)) {
            result = service.lookup("lucid");
            refreshed = service.refresh("lucid");
            warnings = log.warnings();
        }

        assertTrue(result.success());
        assertTrue(refreshed.success());
        assertEquals(3, warnings.size(), "cache read and write failures, then the refresh's write failure");
        assertTrue(warnings.stream().allMatch(record -> record.getThrown() instanceof SQLException));
    }

    @Test
    void fallbackAiServiceLogsProviderFailure() {
        AiService failing = new AiService() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String explain(WordCard word) {
                throw new IllegalStateException("AI provider returned HTTP 401.");
            }
        };
        FallbackAiService service = new FallbackAiService(failing, availableAi("mock"));

        String response;
        List<LogRecord> warnings;
        try (LogCapture log = LogCapture.of(FallbackAiService.class)) {
            response = service.explain(WordCard.createNew(1, "lucid", "清晰的"));
            warnings = log.warnings();
        }

        assertTrue(response.startsWith("mock"));
        assertEquals(1, warnings.size());
        assertEquals("AI provider returned HTTP 401.", warnings.get(0).getThrown().getMessage());
    }

    @Test
    void backupImportLogsReviewLogsItCouldNotRestore() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("backup.db"));
        Deck deck = new DeckRepository(databaseManager).ensureDefaultDeck();
        WordRepository wordRepository = new WordRepository(databaseManager);
        BackupService backupService = new BackupService(new DeckRepository(databaseManager), wordRepository,
            new ReviewLogRepository(databaseManager), new GoalRepository(databaseManager),
            new AchievementRepository(databaseManager), databaseManager, new WordValidationService());
        Path backup = tempDir.resolve("backup.json");
        Files.writeString(backup, """
            {
              "version": 1,
              "words": [
                {"english":"lucid","chinese":"清晰的","phonetic":"","partOfSpeech":"","exampleSentence":"","note":"","tags":""}
              ],
              "reviewLogs": [
                {"wordEnglish":"lucid","reviewedAt":"2026-01-02T10:00:00","userAnswer":"清晰的","correctAnswer":"清晰的","similarity":"1.0","rating":"GOOD","elapsedMillis":"900"},
                {"wordEnglish":"lucid","reviewedAt":"2026-01-03T10:00:00","userAnswer":"x","correctAnswer":"清晰的","similarity":"0.0","rating":"BOGUS","elapsedMillis":"900"},
                {"wordEnglish":"lucid","reviewedAt":"2026-01-04T10:00:00","userAnswer":"x","correctAnswer":"清晰的","similarity":"oops","rating":"AGAIN","elapsedMillis":"900"}
              ]
            }
            """, StandardCharsets.UTF_8);

        BackupRestoreResult result;
        List<LogRecord> warnings;
        try (LogCapture log = LogCapture.of(BackupService.class)) {
            result = backupService.importJsonBackup(backup, deck.getId());
            warnings = log.warnings();
        }

        assertEquals(1, result.wordsInserted());
        assertEquals(1, result.logsInserted());
        assertEquals(2, result.invalidRows().size());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).getMessage().contains("Skipped 2 invalid row(s)"));
        assertTrue(warnings.get(0).getMessage().contains("unknown rating \"BOGUS\""));
        assertInstanceOf(IllegalArgumentException.class, warnings.get(0).getThrown());
    }

    private static AiService availableAi(String response) {
        return new AiService() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String explain(WordCard word) {
                return response;
            }
        };
    }
}
