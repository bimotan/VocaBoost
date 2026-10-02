package com.vocabtrainer.service;

import com.vocabtrainer.domain.DictionaryEntry;
import com.vocabtrainer.domain.DictionaryLookupResult;
import com.vocabtrainer.domain.LookupOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The configured dictionary API against a local HTTP server (review findings D5, D8, F4). */
class HttpDictionaryServiceTest {
    private final StubHttpServer server = newServer();
    /** The failures are logged; keep them off the console. */
    private final LogCapture httpLog = LogCapture.of(HttpLookup.class);
    private final LogCapture serviceLog = LogCapture.of(HttpDictionaryService.class);

    @AfterEach
    void stopServer() {
        server.close();
        httpLog.close();
        serviceLog.close();
    }

    @Test
    void readsEntriesFromAnArrayAndSendsTheWordAndKey() {
        server.answer("/lookup", 200, """
            [{"word":"lucid","chinese":"清晰的; 易懂的","pos":"adjective","phonetic":"/ˈluːsɪd/",
              "example":"a lucid explanation"}]
            """);

        DictionaryLookupResult result = service("secret-key").lookup("  lucid ");

        assertEquals(LookupOutcome.FOUND, result.outcome());
        assertEquals(List.of(new DictionaryEntry("lucid", "清晰的; 易懂的", "adjective", "/ˈluːsɪd/",
            "a lucid explanation", HttpDictionaryService.SOURCE, "")), result.entries());
        StubHttpServer.Request request = server.requests().get(0);
        assertEquals("word=lucid", request.rawQuery());
        assertEquals("Bearer secret-key", request.headers().getFirst("Authorization"));
        assertEquals("secret-key", request.headers().getFirst("X-API-Key"));
        assertTrue(request.headers().getFirst("User-Agent").startsWith("VocaBoost/"));
    }

    @Test
    void encodesTheWordAsOneQueryValue() {
        server.answer("/lookup", 200, "[{\"chinese\":\"放弃\"}]");

        new HttpDictionaryService(server.uri("/lookup?lang=zh").toString(), "", StubHttpServer.client(),
            Duration.ofSeconds(5)).lookup("give up & go");

        assertEquals("lang=zh&word=give%20up%20%26%20go", server.requests().get(0).rawQuery());
    }

    @Test
    void escapedQuotesAndUnicodeEscapesAreDecodedInsteadOfCuttingTheValue() {
        // The regex parser stopped at the first quote, even an escaped one (finding D8).
        server.answer("/lookup", 200, """
            {"translation":"to say \\"firmly\\"","example":"He said \\"it is lucid\\" today.",
             "phonetic":"\\u0259\\u02c8v\\u025c\\u02d0"}
            """);

        DictionaryEntry entry = service("").lookup("aver").entries().get(0);

        assertEquals("to say \"firmly\"", entry.chinese());
        assertEquals("He said \"it is lucid\" today.", entry.example());
        assertEquals("əˈvɜː", entry.phonetic());
        assertEquals("aver", entry.english());
    }

    @Test
    void nestedSensesInheritTheEntryAndMetadataIsNotAnEntry() {
        server.answer("/lookup", 200, """
            {"data":{"word":"aver","phonetic":"əˈvɜː","senses":[
                {"pos":"verb","translation":"断言"},
                {"pos":"verb","meaning":["坚称","声明"],"definition":"to state firmly"}]},
             "meta":{"definition":"rate limit notice","chinese":"不是词条"}}
            """);

        List<DictionaryEntry> entries = service("").lookup("aver").entries();

        assertEquals(List.of(
            new DictionaryEntry("aver", "断言", "verb", "əˈvɜː", "", HttpDictionaryService.SOURCE, ""),
            new DictionaryEntry("aver", "坚称; 声明", "verb", "əˈvɜː", "", HttpDictionaryService.SOURCE,
                "to state firmly")), entries);
    }

    @Test
    void anEnglishDefinitionStaysADefinitionAndNeverBecomesTheChineseMeaning() {
        server.answer("/lookup", 200, "{\"entries\":[{\"word\":\"aver\",\"definition\":\"to state firmly\"}]}");

        DictionaryEntry entry = service("").lookup("aver").entries().get(0);

        assertEquals("", entry.chinese());
        assertEquals("to state firmly", entry.definition());
    }

    @Test
    void entriesWithAChineseMeaningComeBeforeEnglishOnlyOnes() {
        server.answer("/lookup", 200, """
            {"word":"lucid","definition":"easily understood","senses":[{"chinese":"清晰的"},{"definition":"bright"}]}
            """);

        List<DictionaryEntry> entries = service("").lookup("lucid").entries();

        assertEquals(List.of("清晰的", "", ""), entries.stream().map(DictionaryEntry::chinese).toList());
        assertEquals(List.of("", "easily understood", "bright"), entries.stream().map(DictionaryEntry::definition).toList());
    }

    @Test
    void anAnswerWithoutEntriesOrA404MeansNotFound() {
        server.answer("/lookup", 200, "{\"entries\":[]}");
        assertEquals(LookupOutcome.NOT_FOUND, service("").lookup("snarkle").outcome());

        server.answer("/lookup", 404, "{\"error\":\"no such word\"}");
        DictionaryLookupResult result = service("").lookup("snarkle");
        assertEquals(LookupOutcome.NOT_FOUND, result.outcome());
        assertEquals("词条未找到：词典 API 没有该词条。", result.message());
    }

    @Test
    void refusedRateLimitedAndFailingApisAreNotReportedAsNotFound() {
        server.answer("/lookup", 401, "{\"error\":\"bad key\"}");
        DictionaryLookupResult unauthorized = service("wrong").lookup("lucid");
        assertEquals(LookupOutcome.AUTH_ERROR, unauthorized.outcome());
        assertEquals("词典 API：拒绝了请求（HTTP 401）。请检查 DICTIONARY_API_KEY。", unauthorized.message());

        server.answer("/lookup", 403, "");
        assertEquals(LookupOutcome.AUTH_ERROR, service("wrong").lookup("lucid").outcome());

        server.answer("/lookup", StubHttpServer.Answer.json(429, "{}").withHeader("Retry-After", "30"));
        DictionaryLookupResult limited = service("").lookup("lucid");
        assertEquals(LookupOutcome.RATE_LIMITED, limited.outcome());
        assertEquals("词典 API：查询次数受限（HTTP 429），请在 30 秒后再试。", limited.message());

        server.answer("/lookup", 500, "oops");
        DictionaryLookupResult failed = service("").lookup("lucid");
        assertEquals(LookupOutcome.SERVICE_ERROR, failed.outcome());
        assertEquals("词典 API：服务出错（HTTP 500）。", failed.message());
        assertTrue(failed.unavailable());
    }

    @Test
    void malformedJsonIsABadResponse() {
        server.answer("/lookup", 200, "<html>Sign in</html>");

        DictionaryLookupResult result = service("").lookup("lucid");

        assertEquals(LookupOutcome.BAD_RESPONSE, result.outcome());
        assertTrue(result.message().startsWith("词典 API：返回的内容不是有效的 JSON（"), result.message());
    }

    @Test
    void aSlowApiTimesOut() {
        server.answer("/lookup", StubHttpServer.Answer.json(200, "[{\"chinese\":\"太晚了\"}]").after(Duration.ofSeconds(5)));

        long start = System.nanoTime();
        DictionaryLookupResult result = new HttpDictionaryService(server.uri("/lookup").toString(), "",
            StubHttpServer.client(), Duration.ofMillis(300)).lookup("lucid");

        assertEquals(LookupOutcome.TIMEOUT, result.outcome());
        assertEquals("词典 API：0.3 秒内没有响应。", result.message());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(4)) < 0);
    }

    @Test
    void anUnreachableApiIsANetworkError() {
        String address = server.uri("/lookup").toString();
        server.close();

        DictionaryLookupResult result = new HttpDictionaryService(address, "", StubHttpServer.client()).lookup("lucid");

        assertEquals(LookupOutcome.NETWORK_ERROR, result.outcome());
        assertTrue(result.message().startsWith("词典 API：无法连接（"), result.message());
        assertEquals(1, httpLog.warnings().size(), "why the API could not be reached is logged");
    }

    @Test
    void anInvalidAddressIsReportedAsSuch() {
        DictionaryLookupResult result = new HttpDictionaryService("ftp://example.com/x", "", StubHttpServer.client())
            .lookup("lucid");

        assertEquals(LookupOutcome.SERVICE_ERROR, result.outcome());
        assertEquals("词典 API：地址无效（DICTIONARY_API_BASE_URL = ftp://example.com/x）。", result.message());
    }

    @Test
    void anInterruptedLookupStopsAtOnceAndKeepsTheInterruptFlag() throws Exception {
        server.answer("/lookup", StubHttpServer.Answer.json(200, "[{\"chinese\":\"太晚了\"}]").after(Duration.ofSeconds(10)));
        HttpDictionaryService service = new HttpDictionaryService(server.uri("/lookup").toString(), "",
            StubHttpServer.client(), Duration.ofSeconds(20));
        AtomicReference<DictionaryLookupResult> result = new AtomicReference<>();
        AtomicReference<Boolean> flagKept = new AtomicReference<>();
        Thread lookup = new Thread(() -> {
            result.set(service.lookup("lucid"));
            flagKept.set(Thread.currentThread().isInterrupted());
        });

        lookup.start();
        server.awaitRequest();
        lookup.interrupt();
        lookup.join(5_000);

        assertFalse(lookup.isAlive(), "the lookup must end when its thread is interrupted");
        assertEquals(LookupOutcome.INTERRUPTED, result.get().outcome());
        assertTrue(flagKept.get());
    }

    private HttpDictionaryService service(String apiKey) {
        return new HttpDictionaryService(server.uri("/lookup").toString(), apiKey, StubHttpServer.client(),
            Duration.ofSeconds(5));
    }

    private static StubHttpServer newServer() {
        try {
            return new StubHttpServer();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot start the stub dictionary server", e);
        }
    }
}
