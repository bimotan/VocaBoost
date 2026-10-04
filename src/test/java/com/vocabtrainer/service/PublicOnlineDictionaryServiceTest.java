package com.vocabtrainer.service;

import com.vocabtrainer.domain.DictionaryEntry;
import com.vocabtrainer.domain.DictionaryLookupResult;
import com.vocabtrainer.domain.LookupOutcome;
import com.vocabtrainer.domain.VerificationStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * dictionaryapi.dev and Wiktionary against a local HTTP server that answers like them (review
 * findings D4, D5, D9, F4): asked at the same time, in priority order by a common deadline.
 */
class PublicOnlineDictionaryServiceTest {
    private static final String LUCID = """
        [{"word":"lucid","phonetic":"/ˈluːsɪd/","meanings":[{"partOfSpeech":"adjective","definitions":[
            {"definition":"Clear; easily understood.","example":"a lucid explanation"},
            {"definition":"Mentally sound."}]}]}]
        """;
    private static final String DICTIONARY_API_NOT_FOUND = """
        {"title":"No Definitions Found","message":"Sorry pal, we couldn't find definitions for the word you were looking for."}
        """;

    private final StubHttpServer server = newServer();
    private final LogCapture httpLog = LogCapture.of(HttpLookup.class);
    private final LogCapture serviceLog = LogCapture.of(PublicOnlineDictionaryService.class);
    private final PublicOnlineDictionaryService service = new PublicOnlineDictionaryService(StubHttpServer.client(),
        server.uri("/dictapi/"), server.uri("/wiki/"), Duration.ofMillis(500));

    @AfterEach
    void stopServer() {
        server.close();
        httpLog.close();
        serviceLog.close();
    }

    @Test
    void dictionaryApiDevAnswersFirstEvenWhenWiktionaryIsFaster() {
        server.answer("/dictapi/lucid", StubHttpServer.Answer.json(200, LUCID).after(Duration.ofMillis(300)));
        server.answer("/wiki/lucid", 200, """
            {"en":[{"partOfSpeech":"Adjective","definitions":[{"definition":"Clear."}]}]}
            """);

        DictionaryLookupResult result = service.lookup("lucid");

        assertEquals(LookupOutcome.FOUND, result.outcome());
        assertEquals(List.of(
            new DictionaryEntry("lucid", "", "adjective", "/ˈluːsɪd/", "a lucid explanation",
                PublicOnlineDictionaryService.DICTIONARY_API_SOURCE, "Clear; easily understood."),
            new DictionaryEntry("lucid", "", "adjective", "/ˈluːsɪd/", "",
                PublicOnlineDictionaryService.DICTIONARY_API_SOURCE, "Mentally sound.")), result.entries());
        StubHttpServer.Request dictionaryApi = server.requests().stream()
            .filter(request -> request.path().equals("/dictapi/lucid")).findFirst().orElseThrow();
        assertEquals("VocaBoost/1.0 (https://github.com/bimotan/VocaBoost)",
            dictionaryApi.headers().getFirst("User-Agent"));
    }

    @Test
    void aSlowDictionaryApiDevDelaysAWiktionaryAnswerOnlyUntilTheDeadline() {
        PublicOnlineDictionaryService withDeadline = new PublicOnlineDictionaryService(StubHttpServer.client(),
            server.uri("/dictapi/"), server.uri("/wiki/"), Duration.ofSeconds(5), Duration.ofMillis(800));
        server.answer("/dictapi/petrichor", StubHttpServer.Answer.json(200, LUCID).after(Duration.ofSeconds(4)));
        server.answer("/wiki/petrichor", 200, """
            {"en":[{"partOfSpeech":"Noun","definitions":[{"definition":"The smell of rain on dry earth."}]}]}
            """);

        long start = System.nanoTime();
        DictionaryLookupResult result = withDeadline.lookup("petrichor");
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertEquals(LookupOutcome.FOUND, result.outcome());
        assertEquals(PublicOnlineDictionaryService.WIKTIONARY_SOURCE, result.entries().get(0).source());
        assertTrue(took.compareTo(Duration.ofMillis(700)) >= 0, "dictionaryapi.dev is waited for until the deadline: " + took);
        assertTrue(took.compareTo(Duration.ofMillis(2500)) < 0, "but not for its 4 seconds: " + took);
    }

    @Test
    void aSlowWiktionaryDoesNotDelayADictionaryApiDevAnswer() {
        server.answer("/dictapi/lucid", 200, LUCID);
        server.answer("/wiki/lucid", StubHttpServer.Answer.json(200, "{}").after(Duration.ofSeconds(4)));

        long start = System.nanoTime();
        DictionaryLookupResult result = new PublicOnlineDictionaryService(StubHttpServer.client(),
            server.uri("/dictapi/"), server.uri("/wiki/"), Duration.ofSeconds(5)).lookup("lucid");
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertEquals(LookupOutcome.FOUND, result.outcome());
        assertTrue(took.compareTo(Duration.ofSeconds(2)) < 0, "not waiting for Wiktionary: " + took);
    }

    @Test
    void aDictionaryThatMissesTheDeadlineMeansTheWordCouldNotBeChecked() {
        PublicOnlineDictionaryService withDeadline = new PublicOnlineDictionaryService(StubHttpServer.client(),
            server.uri("/dictapi/"), server.uri("/wiki/"), Duration.ofSeconds(5), Duration.ofMillis(600));
        server.answer("/dictapi/petrichor", StubHttpServer.Answer.json(200, LUCID).after(Duration.ofSeconds(4)));
        server.answer("/wiki/petrichor", 404, "{}");

        DictionaryLookupResult result = withDeadline.lookup("petrichor");

        assertEquals(LookupOutcome.TIMEOUT, result.outcome(), "not NOT_FOUND: dictionaryapi.dev may have it");
        assertEquals("dictionaryapi.dev: no answer within 0.6 seconds. | Not found: Wiktionary does not have this word.",
            result.message());
        assertEquals(VerificationStatus.UNCHECKED, withDeadline.verify("petrichor").status());
    }

    @Test
    void dictionaryApiDevGivesSynonymsAntonymsAndARecording() {
        server.answer("/dictapi/abate", 200, """
            [{"word":"abate","phonetics":[{"text":"/əˈbeɪt/","audio":""},
                {"audio":"https://api.dictionaryapi.dev/media/pronunciations/en/abate-uk.mp3"},
                {"text":"/əˈbeɪt/","audio":"https://api.dictionaryapi.dev/media/pronunciations/en/abate-us.mp3"}],
              "meanings":[{"partOfSpeech":"verb","synonyms":["subside","diminish"],"antonyms":["intensify"],
                "definitions":[{"definition":"To lessen in force or intensity.","synonyms":["wane","subside"],
                                "antonyms":[]}]},
                {"partOfSpeech":"noun","synonyms":[],"antonyms":[],"definitions":[{"definition":"Abatement."}]}]}]
            """);

        DictionaryLookupResult result = service.lookup("abate");

        assertEquals(LookupOutcome.FOUND, result.outcome());
        DictionaryEntry verb = result.entries().get(0);
        assertEquals("/əˈbeɪt/", verb.phonetic(), "from phonetics when there is no phonetic");
        assertEquals(List.of("wane", "subside", "diminish"), verb.synonyms());
        assertEquals(List.of("intensify"), verb.antonyms());
        assertEquals("https://api.dictionaryapi.dev/media/pronunciations/en/abate-us.mp3", verb.audioUrl(),
            "the American recording when there are several");
        DictionaryEntry noun = result.entries().get(1);
        assertEquals(List.of(), noun.synonyms());
        assertEquals(verb.audioUrl(), noun.audioUrl(), "the recording is the word's");
        assertEquals(new WordExtras(List.of("wane", "subside", "diminish"), List.of("intensify"), verb.audioUrl()),
            WordExtras.of("abate", result.entries()));
    }

    @Test
    void wiktionaryAnswersWithItsEnglishSectionAsPlainText() {
        server.answer("/dictapi/petrichor", 404, DICTIONARY_API_NOT_FOUND);
        server.answer("/wiki/petrichor", 200, """
            {"en":[{"partOfSpeech":"Noun","language":"English","definitions":[
                {"definition":"The <a rel=\\"mw:WikiLink\\" href=\\"/wiki/smell\\">smell</a> of rain on dry earth &amp; stone&#46;",
                 "examples":["<i>The petrichor</i> after the storm."]}]}],
             "fr":[{"partOfSpeech":"Nom","language":"French","definitions":[{"definition":"pétrichor (odeur)"}]}]}
            """);

        DictionaryLookupResult result = service.lookup("petrichor");

        assertEquals(LookupOutcome.FOUND, result.outcome());
        assertEquals(List.of(new DictionaryEntry("petrichor", "", "Noun", "", "The petrichor after the storm.",
            PublicOnlineDictionaryService.WIKTIONARY_SOURCE, "The smell of rain on dry earth & stone.")),
            result.entries());
    }

    @Test
    void aWiktionaryPageWithoutAnEnglishSectionDoesNotVerifyTheWord() {
        // "tabel" is Dutch and Danish for "table": a typo of an English word that other languages have.
        server.answer("/dictapi/tabel", 404, DICTIONARY_API_NOT_FOUND);
        server.answer("/wiki/tabel", 200, """
            {"nl":[{"partOfSpeech":"Noun","language":"Dutch","definitions":[{"definition":"<a>table</a>"}]}],
             "da":[{"partOfSpeech":"Noun","language":"Danish","definitions":[{"definition":"table, chart"}]}]}
            """);

        DictionaryLookupResult result = service.lookup("tabel");

        assertEquals(LookupOutcome.NOT_FOUND, result.outcome());
        assertEquals("Not found: dictionaryapi.dev does not have this word. | Not found: Wiktionary has no English entry for this word.",
            result.message());
        assertEquals(VerificationStatus.UNVERIFIED, service.verify("tabel").status());
    }

    @Test
    void anEnglishEntryThatOnlyNamesAMisspellingIsNotFound() {
        server.answer("/wiki/recieve", 200, """
            {"en":[{"partOfSpeech":"Verb","language":"English","definitions":[
                {"definition":"<span class=\\"form-of-definition\\">Misspelling of <a>receive</a>.</span>"}]}]}
            """);

        DictionaryLookupResult result = service.lookup("recieve");

        assertEquals(LookupOutcome.NOT_FOUND, result.outcome());
        assertTrue(result.message().endsWith("Not found: Wiktionary lists this word only as a misspelling (Misspelling of receive.)."),
            result.message());
    }

    @Test
    void aMisspellingAfterAUsageLabelIsNotFoundEither() {
        // Wiktionary renders labels before the definition: "(nonstandard) Misspelling of a lot."
        server.answer("/wiki/alot", 200, """
            {"en":[{"partOfSpeech":"Adverb","language":"English","definitions":[
                {"definition":"<span class=\\"usage-label-sense\\"><span class=\\"ib-brac\\">(</span><span class=\\"ib-content\\">nonstandard</span><span class=\\"ib-brac\\">)</span></span> <span class=\\"form-of-definition\\">Misspelling of <a>a lot</a>.</span>"},
                {"definition":"(proscribed, common) Misspelling of <a>a lot</a>."}]}]}
            """);

        DictionaryLookupResult result = service.lookup("alot");

        assertEquals(LookupOutcome.NOT_FOUND, result.outcome(), result.toString());
        assertTrue(result.message().endsWith(" ((nonstandard) Misspelling of a lot.)."), result.message());
    }

    @Test
    void aCapitalisedWordIsAlsoLookedUpInLowerCaseOnWiktionary() {
        server.answer("/wiki/lucid", 200, """
            {"en":[{"partOfSpeech":"Adjective","definitions":[{"definition":"Clear."}]}]}
            """);

        DictionaryLookupResult result = service.lookup("Lucid");

        assertEquals(LookupOutcome.FOUND, result.outcome());
        assertEquals(Set.of("/dictapi/Lucid", "/wiki/Lucid", "/wiki/lucid"), Set.copyOf(server.paths()));
    }

    @Test
    void aPhraseIsSentAsOneEncodedPathSegment() {
        service.lookup("give up");

        assertEquals(Set.of("/dictapi/give%20up", "/wiki/give_up"),
            server.requests().stream().map(StubHttpServer.Request::rawPath).collect(Collectors.toSet()));
    }

    @Test
    void aRateLimitIsNotReportedAsNotFound() {
        server.answer("/dictapi/lucid", StubHttpServer.Answer.json(429, "{}").withHeader("Retry-After", "60"));

        DictionaryLookupResult result = service.lookup("lucid");

        assertEquals(LookupOutcome.RATE_LIMITED, result.outcome());
        assertEquals("dictionaryapi.dev: too many requests (HTTP 429): try again in 60 seconds. | Not found: Wiktionary does not have this word.",
            result.message());
        assertEquals(VerificationStatus.UNCHECKED, service.verify("lucid").status());
    }

    @Test
    void aWiktionaryTimeoutMeansTheWordCouldNotBeChecked() {
        server.answer("/dictapi/petrichor", 404, DICTIONARY_API_NOT_FOUND);
        server.answer("/wiki/petrichor", StubHttpServer.Answer.json(200, "{}").after(Duration.ofSeconds(5)));

        DictionaryLookupResult result = service.lookup("petrichor");

        assertEquals(LookupOutcome.TIMEOUT, result.outcome());
        assertEquals("Not found: dictionaryapi.dev does not have this word. | Wiktionary: no answer within 0.5 seconds.", result.message());
    }

    @Test
    void serverErrorsAndMalformedJsonAreReportedAsSuch() {
        server.answer("/dictapi/lucid", 500, "Internal Server Error");
        assertEquals(LookupOutcome.SERVICE_ERROR, service.lookup("lucid").outcome());

        server.answer("/dictapi/lucid", 200, "[{\"word\":\"lucid\",");
        DictionaryLookupResult malformed = service.lookup("lucid");
        assertEquals(LookupOutcome.BAD_RESPONSE, malformed.outcome());
        assertTrue(malformed.message().startsWith("dictionaryapi.dev: the answer is not valid JSON ("), malformed.message());
    }

    @Test
    void anInterruptedLookupAsksNothing() {
        server.answer("/dictapi/lucid", 200, LUCID);
        Thread.currentThread().interrupt();
        DictionaryLookupResult result;
        try {
            result = service.lookup("lucid");
        } finally {
            assertTrue(Thread.interrupted(), "the interrupt flag must be kept");
        }

        assertEquals(LookupOutcome.INTERRUPTED, result.outcome());
        assertEquals(List.of(), server.paths());
    }

    @Test
    void interruptingAWaitingLookupStopsItAndKeepsTheFlag() throws Exception {
        server.answer("/dictapi/lucid", StubHttpServer.Answer.json(200, LUCID).after(Duration.ofSeconds(4)));
        server.answer("/wiki/lucid", StubHttpServer.Answer.json(200, "{}").after(Duration.ofSeconds(4)));
        PublicOnlineDictionaryService slow = new PublicOnlineDictionaryService(StubHttpServer.client(),
            server.uri("/dictapi/"), server.uri("/wiki/"), Duration.ofSeconds(5));
        AtomicReference<DictionaryLookupResult> result = new AtomicReference<>();
        AtomicBoolean keptFlag = new AtomicBoolean();
        Thread lookup = new Thread(() -> {
            result.set(slow.lookup("lucid"));
            keptFlag.set(Thread.currentThread().isInterrupted());
        });
        lookup.start();
        server.awaitRequest();

        lookup.interrupt();
        lookup.join(2000);

        assertFalse(lookup.isAlive(), "the lookup stopped waiting");
        assertEquals(LookupOutcome.INTERRUPTED, result.get().outcome());
        assertTrue(keptFlag.get(), "the interrupt flag must be kept");
    }

    @Test
    void htmlIsReducedToText() {
        assertEquals("a < b & \"c\" — d", PublicOnlineDictionaryService.plainText(
            "<b>a</b> &lt; b &amp; &quot;c&quot;&nbsp;&#x2014; <a href=\"x\">d</a>"));
        assertEquals("keeps &unknown; and &#xZZ;", PublicOnlineDictionaryService.plainText("keeps &unknown; and &#xZZ;"));
    }

    private static StubHttpServer newServer() {
        try {
            return new StubHttpServer();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start the stub dictionary server", e);
        }
    }
}
