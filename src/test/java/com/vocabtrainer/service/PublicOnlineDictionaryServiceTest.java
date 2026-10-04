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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * dictionaryapi.dev and Wiktionary against a local HTTP server that answers like them (review
 * findings D5, D9, F4).
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
    void dictionaryApiDevAnswersFirstAndWiktionaryIsNotAsked() {
        server.answer("/dictapi/lucid", 200, LUCID);

        DictionaryLookupResult result = service.lookup("lucid");

        assertEquals(LookupOutcome.FOUND, result.outcome());
        assertEquals(List.of(
            new DictionaryEntry("lucid", "", "adjective", "/ˈluːsɪd/", "a lucid explanation",
                PublicOnlineDictionaryService.DICTIONARY_API_SOURCE, "Clear; easily understood."),
            new DictionaryEntry("lucid", "", "adjective", "/ˈluːsɪd/", "",
                PublicOnlineDictionaryService.DICTIONARY_API_SOURCE, "Mentally sound.")), result.entries());
        assertEquals(List.of("/dictapi/lucid"), server.paths());
        assertEquals("VocaBoost/1.0 (https://github.com/bimotan/VocaBoost)",
            server.requests().get(0).headers().getFirst("User-Agent"));
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
        assertEquals(List.of("/dictapi/Lucid", "/wiki/Lucid", "/wiki/lucid"), server.paths());
    }

    @Test
    void aPhraseIsSentAsOneEncodedPathSegment() {
        service.lookup("give up");

        assertEquals(List.of("/dictapi/give%20up", "/wiki/give_up"),
            server.requests().stream().map(StubHttpServer.Request::rawPath).toList());
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
    void anInterruptedLookupDoesNotGoOnToWiktionary() {
        server.answer("/dictapi/lucid", 200, LUCID);
        Thread.currentThread().interrupt();
        DictionaryLookupResult result;
        try {
            result = service.lookup("lucid");
        } finally {
            assertTrue(Thread.interrupted(), "the interrupt flag must be kept");
        }

        assertEquals(LookupOutcome.INTERRUPTED, result.outcome());
        assertTrue(server.paths().stream().noneMatch(path -> path.startsWith("/wiki/")), server.paths().toString());
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
