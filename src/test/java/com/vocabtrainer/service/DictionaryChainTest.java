package com.vocabtrainer.service;

import com.vocabtrainer.domain.DictionaryEntry;
import com.vocabtrainer.domain.DictionaryLookupResult;
import com.vocabtrainer.domain.LookupOutcome;
import com.vocabtrainer.domain.VerificationStatus;
import com.vocabtrainer.domain.WordVerificationResult;
import com.vocabtrainer.repository.DictionaryCacheRepository;
import com.vocabtrainer.repository.TestDatabases;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The order in which the dictionary chain asks its dictionaries, what the lookup cache keeps and
 * for how long, and how lookups that found nothing are told apart (review findings C3, D5, D6, F4).
 */
class DictionaryChainTest {
    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 1, 9, 0);

    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    private final MutableClock clock = new MutableClock(START);
    private final ScriptedDictionary api = new ScriptedDictionary(HttpDictionaryService.SOURCE);
    private final ScriptedDictionary online = new ScriptedDictionary(PublicOnlineDictionaryService.DICTIONARY_API_SOURCE);
    private DictionaryCacheRepository cache;

    @BeforeEach
    void openCache() throws Exception {
        cache = new DictionaryCacheRepository(databases.open(tempDir.resolve("vocab.db")));
    }

    @Test
    void anOfflineHitAsksNeitherTheConfiguredApiNorTheOnlineDictionaries() {
        DictionaryService chain = chain(api);

        DictionaryLookupResult result = chain.lookup("abate");

        assertEquals(LocalDictionaryService.STARTER_SOURCE, result.entries().get(0).source());
        assertEquals("Local dictionary", chain.verify("abate").source());
        assertEquals(List.of(), api.asked);
        assertEquals(List.of(), online.asked);
    }

    @Test
    void theConfiguredApiAnswersBeforeAnEnglishOnlyEntryCachedEarlier() throws Exception {
        // Looked up before the API was configured: dictionaryapi.dev's English definition is cached.
        online.found("zeugma", "", "a figure of speech");
        assertEquals("", chain(null).lookup("zeugma").entries().get(0).chinese());
        api.found("zeugma", "轭式修饰法", "");

        DictionaryLookupResult result = chain(api).lookup("zeugma");

        assertEquals("轭式修饰法", result.entries().get(0).chinese());
        assertEquals(HttpDictionaryService.SOURCE, result.entries().get(0).source());
        assertEquals(1, online.calls("zeugma"));
        // A word the API does not have still comes from the cache.
        online.found("petrichor", "", "the smell of rain");
        chain(null).lookup("petrichor");
        DictionaryLookupResult cached = chain(api).lookup("petrichor");
        assertEquals("Loaded from dictionary cache.", cached.message());
        assertEquals(1, online.calls("petrichor"));
        assertEquals(PublicOnlineDictionaryService.DICTIONARY_API_SOURCE, cache.find("petrichor").orElseThrow().source());
    }

    @Test
    void anOnlineResultIsCachedForThirtyDays() throws Exception {
        online.found("petrichor", "", "the smell of rain");
        DictionaryService chain = chain(null);

        chain.lookup("petrichor");
        clock.set(START.plusDays(29));
        DictionaryLookupResult cached = chain.lookup("PETRICHOR");

        assertEquals("Loaded from dictionary cache.", cached.message());
        assertEquals(1, online.calls("petrichor"));

        clock.set(START.plusDays(31));
        DictionaryLookupResult renewed = chain.lookup("petrichor");

        assertEquals(PublicOnlineDictionaryService.DICTIONARY_API_SOURCE + " answered", renewed.message());
        assertEquals(2, online.calls("petrichor"));
        assertEquals(START.plusDays(31), cache.find("petrichor").orElseThrow().createdAt());
    }

    @Test
    void anExpiredEntryStillAnswersWhileTheDictionariesCannotBeAsked() {
        online.found("petrichor", "", "the smell of rain");
        DictionaryService chain = chain(null);
        DictionaryEntry entry = chain.lookup("petrichor").entries().get(0);
        online.answer("petrichor", DictionaryLookupResult.unavailable(LookupOutcome.NETWORK_ERROR, "offline"));
        clock.set(START.plusDays(40));

        DictionaryLookupResult result = chain.lookup("petrichor");

        assertEquals(LookupOutcome.FOUND, result.outcome());
        assertEquals(List.of(entry), result.entries());
        assertEquals("Loaded from dictionary cache (saved 2026-10-01) because the online dictionaries could not be"
            + " asked: offline", result.message());
    }

    @Test
    void notFoundIsRememberedForTenMinutesAndAFailureToAskIsNotRemembered() throws Exception {
        DictionaryService chain = chain(null);

        assertEquals(LookupOutcome.NOT_FOUND, chain.lookup("snarkle").outcome());
        clock.set(START.plusMinutes(9));
        assertEquals(LookupOutcome.NOT_FOUND, chain.lookup("Snarkle").outcome());
        assertEquals(1, online.calls("snarkle"), "adding a word a lookup just missed does not ask again (finding C3)");
        clock.set(START.plusMinutes(11));
        chain.lookup("snarkle");
        assertEquals(2, online.calls("snarkle"));
        assertTrue(cache.find("snarkle").isEmpty(), "a miss is never written to the cache");

        online.answer("quokka", DictionaryLookupResult.unavailable(LookupOutcome.TIMEOUT, "slow"));
        assertEquals(LookupOutcome.TIMEOUT, chain.lookup("quokka").outcome());
        assertEquals(LookupOutcome.TIMEOUT, chain.lookup("quokka").outcome());
        assertEquals(2, online.calls("quokka"));
        assertTrue(cache.find("quokka").isEmpty());
    }

    @Test
    void aRefreshAsksAgainAndKeepsTheCachedEntryOnlyWhileTheDictionaryCannotBeAsked() throws Exception {
        online.found("petrichor", "", "first definition");
        DictionaryService chain = chain(null);
        chain.lookup("petrichor");

        online.answer("petrichor", DictionaryLookupResult.unavailable(LookupOutcome.RATE_LIMITED, "429"));
        DictionaryLookupResult limited = chain.refresh("petrichor");
        assertEquals(LookupOutcome.RATE_LIMITED, limited.outcome(), "a refresh reports why it could not ask");
        assertTrue(cache.find("petrichor").isPresent());

        online.found("petrichor", "", "second definition");
        assertEquals("second definition", chain.refresh("petrichor").entries().get(0).definition());
        assertEquals("second definition", chain.lookup("petrichor").entries().get(0).definition());

        online.answer("petrichor", DictionaryLookupResult.notFound("gone"));
        assertEquals(LookupOutcome.NOT_FOUND, chain.refresh("petrichor").outcome());
        assertTrue(cache.find("petrichor").isEmpty(), "the dictionary no longer has the word");

        online.found("petrichor", "", "third definition");
        assertEquals("third definition", chain.refresh("petrichor").entries().get(0).definition(),
            "a refresh ignores the remembered miss");
        assertEquals(5, online.calls("petrichor"));
    }

    @Test
    void aCacheEntryThatCannotBeReadIsLookedUpAgainAndReplaced() throws Exception {
        // Earlier versions threw the Base64 decoder's IllegalArgumentException out of the lookup.
        cache.save("petrichor", String.join("\t", "%%", "%%", "%%", "%%", "%%", "%%"), "dictionary", START);
        online.found("petrichor", "", "the smell of rain");

        DictionaryLookupResult result;
        try (LogCapture log = LogCapture.of(CachingDictionaryService.class)) {
            result = chain(null).lookup("petrichor");
            assertEquals(1, log.warnings().size());
        }

        assertEquals("the smell of rain", result.entries().get(0).definition());
        assertEquals(PublicOnlineDictionaryService.DICTIONARY_API_SOURCE, cache.find("petrichor").orElseThrow().source());
    }

    @Test
    void anEntryAnEarlierVersionCachedFromWiktionaryIsLookedUpAgain() throws Exception {
        // Earlier versions cached every answer with source "dictionary", Wiktionary's raw HTML from
        // any language's section and misspelling-only entries included (finding D9).
        ScriptedDictionary wiktionary = new ScriptedDictionary(PublicOnlineDictionaryService.WIKTIONARY_SOURCE);
        wiktionary.found("recieve", "", "<span>Misspelling of receive.</span>");
        online.found("zeugma", "", "a figure of speech");
        DictionaryService before = DictionaryServiceFactory.compose(new LocalDictionaryService(), null,
            new CompositeDictionaryService(List.of(online, wiktionary)), cache, clock);
        before.lookup("recieve");
        before.lookup("zeugma");
        for (String word : List.of("recieve", "zeugma")) {
            cache.save(word, cache.find(word).orElseThrow().payload(), "dictionary", START);
        }
        online.answer("recieve", DictionaryLookupResult.unavailable(LookupOutcome.NETWORK_ERROR, "offline"));

        DictionaryLookupResult legacyWiktionary = chain(null).lookup("recieve");
        DictionaryLookupResult legacyDictionaryApi = chain(null).lookup("zeugma");

        assertEquals(LookupOutcome.NETWORK_ERROR, legacyWiktionary.outcome(), "not verified by the old entry");
        assertTrue(cache.find("recieve").isEmpty());
        assertEquals("Loaded from dictionary cache.", legacyDictionaryApi.message(),
            "dictionaryapi.dev entries were read as they are now");
        assertEquals(1, online.calls("zeugma"));
    }

    @Test
    void nothingFoundIsNotFoundOnlyWhenEveryDictionaryAnsweredSo() {
        ScriptedDictionary first = new ScriptedDictionary("first");
        ScriptedDictionary second = new ScriptedDictionary("second");
        second.answer("lucid", DictionaryLookupResult.unavailable(LookupOutcome.NETWORK_ERROR, "second: offline"));
        DictionaryService chain = new CompositeDictionaryService(List.of(first, second, new ScriptedDictionary("third")));

        DictionaryLookupResult unreachable = chain.lookup("lucid");
        assertEquals(LookupOutcome.NETWORK_ERROR, unreachable.outcome());
        assertEquals("first has no lucid | second: offline | third has no lucid", unreachable.message());
        WordVerificationResult unchecked = chain.verify("lucid");
        assertEquals(VerificationStatus.UNCHECKED, unchecked.status());
        assertEquals(LookupOutcome.NETWORK_ERROR, unchecked.outcome());

        DictionaryLookupResult missing = chain.lookup("snarkle");
        assertEquals(LookupOutcome.NOT_FOUND, missing.outcome());
        assertEquals(VerificationStatus.UNVERIFIED, chain.verify("snarkle").status());
        assertFalse(chain.verify("snarkle").found());
    }

    @Test
    void anInterruptedLookupStopsTheChain() {
        api.answer("petrichor", DictionaryLookupResult.interrupted());
        DictionaryService chain = chain(api);

        DictionaryLookupResult result = chain.lookup("petrichor");

        assertEquals(LookupOutcome.INTERRUPTED, result.outcome());
        assertEquals(List.of(), online.asked);
        assertEquals(LookupOutcome.INTERRUPTED, chain.verify("petrichor").outcome());
        assertEquals(List.of(), online.asked);
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    void theFactoryAsksTheApiNamedInTheEnvironmentAfterTheOfflineDictionaries() throws Exception {
        try (StubHttpServer server = new StubHttpServer()) {
            server.answer("/api", 200, "[{\"chinese\":\"雨后泥土的气味\"}]");
            DictionaryService chain = DictionaryServiceFactory.create(cache, new LocalDictionaryService(), Map.of(
                DictionaryServiceFactory.API_BASE_URL, server.uri("/api").toString(),
                DictionaryServiceFactory.API_KEY, "key"), clock);

            assertEquals(LocalDictionaryService.STARTER_SOURCE, chain.lookup("abate").entries().get(0).source());
            assertEquals(List.of(), server.requests());

            DictionaryLookupResult result = chain.lookup("petrichor");

            assertEquals("雨后泥土的气味", result.entries().get(0).chinese());
            assertEquals(HttpDictionaryService.SOURCE, result.entries().get(0).source());
            assertEquals("word=petrichor", server.requests().get(0).rawQuery());
            assertEquals("Bearer key", server.requests().get(0).headers().getFirst("Authorization"));
            assertTrue(cache.find("petrichor").isEmpty(), "the configured API is asked every time, not cached");
        }
    }

    private DictionaryService chain(DictionaryService configuredApi) {
        return DictionaryServiceFactory.compose(new LocalDictionaryService(), configuredApi, online, cache, clock);
    }

    /** Answers from a script and counts how often each word was asked for. */
    private static final class ScriptedDictionary implements DictionaryService {
        private final String source;
        private final Map<String, Deque<DictionaryLookupResult>> answers = new HashMap<>();
        private final List<String> asked = new ArrayList<>();

        private ScriptedDictionary(String source) {
            this.source = source;
        }

        void found(String word, String chinese, String definition) {
            answer(word, DictionaryLookupResult.success(source + " answered",
                List.of(new DictionaryEntry(word, chinese, "noun", "", "", source, definition))));
        }

        /** The answers to the next lookups of {@code word}; the last one stays. */
        void answer(String word, DictionaryLookupResult... results) {
            answers.put(word, new ArrayDeque<>(List.of(results)));
        }

        int calls(String word) {
            return (int) asked.stream().filter(word::equals).count();
        }

        @Override
        public DictionaryLookupResult lookup(String english) {
            String word = english.trim().toLowerCase(Locale.ROOT);
            asked.add(word);
            Deque<DictionaryLookupResult> queue = answers.get(word);
            if (queue == null) {
                return DictionaryLookupResult.notFound(source + " has no " + word);
            }
            return queue.size() > 1 ? queue.poll() : queue.peek();
        }

        @Override
        public boolean isConfigured() {
            return true;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(LocalDateTime start) {
            set(start);
        }

        void set(LocalDateTime time) {
            now = time.toInstant(ZoneOffset.UTC);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
