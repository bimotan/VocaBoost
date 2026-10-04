package com.vocabtrainer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vocabtrainer.domain.DictionaryEntry;
import com.vocabtrainer.domain.DictionaryLookupResult;
import com.vocabtrainer.domain.LookupOutcome;
import com.vocabtrainer.util.Messages;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.vocabtrainer.util.Messages.tr;

/**
 * The free online dictionaries, which give English definitions only: dictionaryapi.dev, then the
 * English Wiktionary. Only Wiktionary's English sections count, so a misspelt English word that is
 * a word in another language is not found, and neither is an entry that only says it is a
 * misspelling ("Misspelling of receive."). Wiktionary's definitions are HTML; their markup is
 * removed.
 */
public class PublicOnlineDictionaryService implements DictionaryService {
    public static final URI DICTIONARY_API = URI.create("https://api.dictionaryapi.dev/api/v2/entries/en/");
    public static final URI WIKTIONARY = URI.create("https://en.wiktionary.org/api/rest_v1/page/definition/");
    public static final String DICTIONARY_API_SOURCE = "dictionaryapi.dev";
    public static final String WIKTIONARY_SOURCE = "Wiktionary";

    private static final Logger LOGGER = Logger.getLogger(PublicOnlineDictionaryService.class.getName());
    private static final int MAX_ENTRIES = 5;
    /** "Misspelling of receive.", also after usage labels such as "(nonstandard)" or "(proscribed, common)". */
    private static final Pattern MISSPELLING = Pattern.compile(
        "(?i)^(?:\\([^)]*\\)\\s*)*(?:an? )?(?:common |nonstandard |rare )?misspelling of\\b");
    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]{1,6}|#[0-9]{1,7}|[a-zA-Z]{2,8});");

    private final HttpClient httpClient;
    private final URI dictionaryApi;
    private final URI wiktionary;
    private final Duration timeout;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PublicOnlineDictionaryService() {
        this(HttpLookup.newClient());
    }

    public PublicOnlineDictionaryService(HttpClient httpClient) {
        this(httpClient, DICTIONARY_API, WIKTIONARY, HttpLookup.REQUEST_TIMEOUT);
    }

    /**
     * @param dictionaryApi the dictionaryapi.dev entries endpoint, ending with "/"; the word is appended
     * @param wiktionary    the Wiktionary definition endpoint, ending with "/"; the page title is appended
     * @param timeout       how long one request may take
     */
    public PublicOnlineDictionaryService(HttpClient httpClient, URI dictionaryApi, URI wiktionary, Duration timeout) {
        this.httpClient = httpClient;
        this.dictionaryApi = dictionaryApi;
        this.wiktionary = wiktionary;
        this.timeout = timeout;
    }

    @Override
    public DictionaryLookupResult lookup(String english) {
        String clean = english == null ? "" : english.trim();
        if (clean.isBlank()) {
            return DictionaryLookupResult.notFound(tr("dictionary.enterWord"));
        }
        DictionaryLookupResult dictionaryApiResult = lookupDictionaryApi(clean);
        if (dictionaryApiResult.success() || dictionaryApiResult.outcome() == LookupOutcome.INTERRUPTED) {
            return dictionaryApiResult;
        }
        DictionaryLookupResult wiktionaryResult = lookupWiktionary(clean);
        if (wiktionaryResult.success() || wiktionaryResult.outcome() == LookupOutcome.INTERRUPTED) {
            return wiktionaryResult;
        }
        return CompositeDictionaryService.combine(List.of(dictionaryApiResult, wiktionaryResult));
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    /** "Loaded from Wiktionary.", with a note that the meaning is English only. */
    private static String loadedEnglishOnly(String dictionary) {
        return Messages.sentences(List.of(tr("dictionary.loadedFrom", dictionary), tr("dictionary.online.englishOnly")));
    }

    private DictionaryLookupResult lookupDictionaryApi(String english) {
        return fetch(dictionaryApi.resolve(HttpLookup.pathSegment(english)), DICTIONARY_API_SOURCE,
            tr("dictionary.notFoundIn", DICTIONARY_API_SOURCE), this::parseDictionaryApi, english);
    }

    /** Wiktionary titles are case-sensitive: "Lucid" is looked up as typed, then as "lucid". */
    private DictionaryLookupResult lookupWiktionary(String english) {
        DictionaryLookupResult result = lookupWiktionaryTitle(english);
        String lowerCase = english.toLowerCase(Locale.ROOT);
        if (result.outcome() == LookupOutcome.NOT_FOUND && !lowerCase.equals(english)) {
            return lookupWiktionaryTitle(lowerCase);
        }
        return result;
    }

    private DictionaryLookupResult lookupWiktionaryTitle(String english) {
        return fetch(wiktionary.resolve(HttpLookup.pathSegment(english.replace(' ', '_'))), WIKTIONARY_SOURCE,
            tr("dictionary.notFoundIn", WIKTIONARY_SOURCE), this::parseWiktionary, english);
    }

    private DictionaryLookupResult fetch(URI uri, String dictionary, String notFoundMessage,
                                         BiFunction<String, JsonNode, DictionaryLookupResult> parser, String english) {
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("Accept", "application/json")
            .header("User-Agent", HttpLookup.USER_AGENT)
            .GET()
            .build();
        HttpLookup.Reply reply = HttpLookup.get(httpClient, request, dictionary, notFoundMessage);
        if (!reply.ok()) {
            return reply.failure();
        }
        try {
            return parser.apply(english, objectMapper.readTree(reply.body() == null ? "" : reply.body()));
        } catch (JsonProcessingException e) {
            LOGGER.log(Level.WARNING, dictionary + "'s answer for '" + english + "' is not JSON", e);
            return DictionaryLookupResult.unavailable(LookupOutcome.BAD_RESPONSE,
                tr("dictionary.badJson", dictionary, e.getOriginalMessage()));
        }
    }

    private DictionaryLookupResult parseDictionaryApi(String english, JsonNode root) {
        List<DictionaryEntry> entries = new ArrayList<>();
        // A word it does not know is HTTP 404; any other shape than an array of entries has none.
        JsonNode words = root.isArray() ? root : objectMapper.createArrayNode();
        for (JsonNode wordNode : words) {
            String phonetic = HttpLookup.text(wordNode, "phonetic");
            for (JsonNode meaning : wordNode.path("meanings")) {
                String pos = HttpLookup.text(meaning, "partOfSpeech");
                for (JsonNode definitionNode : meaning.path("definitions")) {
                    String definition = HttpLookup.text(definitionNode, "definition");
                    if (!definition.isBlank() && entries.size() < MAX_ENTRIES) {
                        entries.add(new DictionaryEntry(english, "", pos, phonetic,
                            HttpLookup.text(definitionNode, "example"), DICTIONARY_API_SOURCE, definition));
                    }
                }
            }
        }
        if (entries.isEmpty()) {
            return DictionaryLookupResult.notFound(tr("dictionary.notFoundIn", DICTIONARY_API_SOURCE));
        }
        return DictionaryLookupResult.success(loadedEnglishOnly(DICTIONARY_API_SOURCE), entries);
    }

    /**
     * Reads the English section ({@code "en"}) of a Wiktionary definition page:
     * {@code {"en": [{"partOfSpeech": ..., "definitions": [{"definition": "<html>", "examples": [...]}]}], "fr": ...}}.
     */
    private DictionaryLookupResult parseWiktionary(String english, JsonNode root) {
        List<DictionaryEntry> entries = new ArrayList<>();
        String misspelling = "";
        for (JsonNode usage : root.path("en")) {
            String pos = HttpLookup.text(usage, "partOfSpeech");
            for (JsonNode definitionNode : usage.path("definitions")) {
                String definition = plainText(definitionNode.path("definition").asText(""));
                if (definition.isEmpty()) {
                    continue;
                }
                if (MISSPELLING.matcher(definition).lookingAt()) {
                    misspelling = misspelling.isEmpty() ? definition : misspelling;
                    continue;
                }
                if (entries.size() < MAX_ENTRIES) {
                    entries.add(new DictionaryEntry(english, "", pos, "", firstExample(definitionNode),
                        WIKTIONARY_SOURCE, definition));
                }
            }
        }
        if (!entries.isEmpty()) {
            return DictionaryLookupResult.success(loadedEnglishOnly(WIKTIONARY_SOURCE), entries);
        }
        if (!misspelling.isEmpty()) {
            return DictionaryLookupResult.notFound(tr("dictionary.wiktionary.misspelling", misspelling));
        }
        return DictionaryLookupResult.notFound(tr("dictionary.wiktionary.noEnglish"));
    }

    private static String firstExample(JsonNode definitionNode) {
        for (JsonNode example : definitionNode.path("examples")) {
            String text = plainText(example.asText(""));
            if (!text.isEmpty()) {
                return text;
            }
        }
        return "";
    }

    /** Wiktionary's HTML as plain text: tags removed, character references decoded, whitespace collapsed. */
    static String plainText(String html) {
        String withoutTags = TAG.matcher(html == null ? "" : html).replaceAll("");
        Matcher matcher = ENTITY.matcher(withoutTags);
        StringBuilder text = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(text, Matcher.quoteReplacement(decodeEntity(matcher.group(1), matcher.group())));
        }
        matcher.appendTail(text);
        return text.toString().replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String decodeEntity(String name, String original) {
        try {
            if (name.startsWith("#x") || name.startsWith("#X")) {
                return Character.toString(Integer.parseInt(name.substring(2), 16));
            }
            if (name.startsWith("#")) {
                return Character.toString(Integer.parseInt(name.substring(1)));
            }
        } catch (IllegalArgumentException e) {
            return original;
        }
        return switch (name) {
            case "amp" -> "&";
            case "lt" -> "<";
            case "gt" -> ">";
            case "quot" -> "\"";
            case "apos" -> "'";
            case "nbsp" -> " ";
            case "ndash" -> "–";
            case "mdash" -> "—";
            default -> original;
        };
    }
}
