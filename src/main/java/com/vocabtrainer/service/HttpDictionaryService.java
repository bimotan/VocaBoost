package com.vocabtrainer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vocabtrainer.domain.DictionaryEntry;
import com.vocabtrainer.domain.DictionaryLookupResult;
import com.vocabtrainer.domain.LookupOutcome;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A dictionary API the user runs or subscribes to ({@code DICTIONARY_API_BASE_URL}, optionally
 * {@code DICTIONARY_API_KEY}), asked as {@code GET <base>?word=<word>}, ideally one that returns
 * Chinese meanings.
 *
 * <p>The answer is JSON: an array of entries, or an object whose {@code entries}, {@code data},
 * {@code results} or {@code result} holds them (or that is itself one entry). An entry has the
 * Chinese meaning in {@code chinese}, {@code translation} or {@code meaning} and an English
 * definition in {@code definition}; its senses can be nested in {@code senses}, {@code meanings},
 * {@code definitions} or {@code translations} and inherit the entry's word, phonetic and part of
 * speech. Objects with neither a meaning nor a definition, and fields outside the entries (such
 * as {@code meta}), are ignored. HTTP 404 or an answer without entries means the word is not
 * there; other failures say why the API could not be asked.
 *
 * <p>The key is sent only over https, or over http to this computer; a plain-http address
 * elsewhere is not asked at all while a key is set. The key never appears in a message or log.
 */
public class HttpDictionaryService implements DictionaryService {
    public static final String SOURCE = "Configured API";

    private static final Logger LOGGER = Logger.getLogger(HttpDictionaryService.class.getName());
    private static final String NAME = "词典 API";
    private static final String NOT_FOUND = "词条未找到：词典 API 没有该词条。";
    private static final List<String> ENTRY_CONTAINERS = List.of("entries", "data", "results", "result");
    private static final List<String> SENSE_CONTAINERS = List.of("senses", "meanings", "definitions", "translations");
    private static final int MAX_ENTRIES = 10;

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient httpClient;
    private final Duration timeout;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HttpDictionaryService(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, HttpLookup.newClient());
    }

    public HttpDictionaryService(String baseUrl, String apiKey, HttpClient httpClient) {
        this(baseUrl, apiKey, httpClient, HttpLookup.REQUEST_TIMEOUT);
    }

    /** @param timeout how long one request may take */
    public HttpDictionaryService(String baseUrl, String apiKey, HttpClient httpClient, Duration timeout) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.httpClient = httpClient;
        this.timeout = timeout;
    }

    @Override
    public DictionaryLookupResult lookup(String english) {
        if (!isConfigured()) {
            return DictionaryLookupResult.unavailable(LookupOutcome.SERVICE_ERROR,
                NAME + "：没有配置 DICTIONARY_API_BASE_URL。");
        }
        String clean = english == null ? "" : english.trim();
        if (clean.isBlank()) {
            return DictionaryLookupResult.notFound("Please enter an English word first.");
        }
        URI uri;
        try {
            uri = buildUri(clean);
        } catch (IllegalArgumentException e) {
            LOGGER.log(Level.WARNING, "DICTIONARY_API_BASE_URL is not a valid http(s) URL: " + baseUrl, e);
            return DictionaryLookupResult.unavailable(LookupOutcome.SERVICE_ERROR,
                NAME + "：地址无效（DICTIONARY_API_BASE_URL = " + baseUrl + "）。");
        }
        if (!apiKey.isBlank() && !ApiKeys.isSafeToSendKey(uri)) {
            LOGGER.warning("Not sending DICTIONARY_API_KEY over plain http to " + uri.getHost());
            return DictionaryLookupResult.unavailable(LookupOutcome.SERVICE_ERROR, NAME
                + "：DICTIONARY_API_BASE_URL 使用明文 http，API key 会被明文发送，所以没有查询。请改用 https"
                + "（本机地址 localhost、127.0.0.1、::1 除外）。");
        }
        if (!apiKey.isBlank() && !ApiKeys.isSendable(apiKey)) {
            return DictionaryLookupResult.unavailable(LookupOutcome.AUTH_ERROR,
                NAME + "：DICTIONARY_API_KEY 含有空格或不能放进 HTTP 请求头的字符（例如全角字符或换行），请重新设置。");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("Accept", "application/json")
            .header("User-Agent", HttpLookup.USER_AGENT)
            .GET();
        if (!apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
            builder.header("X-API-Key", apiKey);
        }
        HttpRequest request = builder.build();
        HttpLookup.Reply reply = HttpLookup.get(httpClient, request, NAME, NOT_FOUND);
        if (!reply.ok() && reply.failure().outcome() == LookupOutcome.AUTH_ERROR) {
            return DictionaryLookupResult.unavailable(LookupOutcome.AUTH_ERROR,
                reply.failure().message() + "请检查 DICTIONARY_API_KEY。");
        }
        if (!reply.ok()) {
            return reply.failure();
        }
        List<DictionaryEntry> entries;
        try {
            entries = parseEntries(clean, objectMapper.readTree(reply.body() == null ? "" : reply.body()));
        } catch (JsonProcessingException e) {
            LOGGER.log(Level.WARNING, "The dictionary API's answer for '" + clean + "' is not JSON", e);
            return DictionaryLookupResult.unavailable(LookupOutcome.BAD_RESPONSE,
                NAME + "：返回的内容不是有效的 JSON（" + e.getOriginalMessage() + "）。");
        }
        if (entries.isEmpty()) {
            return DictionaryLookupResult.notFound(NOT_FOUND);
        }
        return DictionaryLookupResult.success("Loaded from configured dictionary API.", entries);
    }

    @Override
    public boolean isConfigured() {
        return !baseUrl.isBlank();
    }

    private URI buildUri(String english) {
        String separator = baseUrl.contains("?") ? "&" : "?";
        URI uri = URI.create(baseUrl + separator + "word=" + HttpLookup.pathSegment(english));
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Not an http(s) URL: " + baseUrl);
        }
        return uri;
    }

    private List<DictionaryEntry> parseEntries(String english, JsonNode root) {
        List<DictionaryEntry> entries = new ArrayList<>();
        Inherited query = new Inherited(english, "", "", "", SOURCE);
        for (JsonNode node : entryNodes(root)) {
            collect(node, query, entries);
        }
        // The lookup box fills the add form with the first entry: one with a Chinese meaning, if any.
        entries.sort(Comparator.comparing(entry -> entry.chinese().isEmpty()));
        return entries;
    }

    private static List<JsonNode> entryNodes(JsonNode root) {
        List<JsonNode> nodes = new ArrayList<>();
        if (root == null) {
            return nodes;
        }
        if (root.isArray()) {
            root.forEach(nodes::add);
            return nodes;
        }
        if (!root.isObject()) {
            return nodes;
        }
        for (String container : ENTRY_CONTAINERS) {
            JsonNode value = root.get(container);
            if (value != null && value.isArray()) {
                value.forEach(nodes::add);
                return nodes;
            }
            if (value != null && value.isObject()) {
                nodes.add(value);
                return nodes;
            }
        }
        nodes.add(root);
        return nodes;
    }

    /** Adds the entry {@code node} describes, if any, and the entries of its nested senses. */
    private static void collect(JsonNode node, Inherited parent, List<DictionaryEntry> entries) {
        if (node == null || !node.isObject() || entries.size() >= MAX_ENTRIES) {
            return;
        }
        Inherited fields = parent.with(node);
        String chinese = HttpLookup.firstText(node, "chinese", "translation", "meaning");
        String definition = HttpLookup.text(node, "definition");
        if (!chinese.isEmpty() || !definition.isEmpty()) {
            entries.add(new DictionaryEntry(fields.english(), chinese, fields.partOfSpeech(), fields.phonetic(),
                fields.example(), fields.source(), definition));
        }
        for (String container : SENSE_CONTAINERS) {
            JsonNode senses = node.get(container);
            if (senses != null && senses.isArray()) {
                for (JsonNode sense : senses) {
                    collect(sense, fields, entries);
                }
            }
        }
    }

    /** The fields a sense takes from the entry it belongs to unless it has its own. */
    private record Inherited(String english, String partOfSpeech, String phonetic, String example, String source) {
        Inherited with(JsonNode node) {
            return new Inherited(
                orElse(HttpLookup.firstText(node, "english", "word"), english),
                orElse(HttpLookup.firstText(node, "partOfSpeech", "pos"), partOfSpeech),
                orElse(phoneticOf(node), phonetic),
                orElse(HttpLookup.firstText(node, "example", "exampleSentence"), example),
                orElse(HttpLookup.text(node, "source"), source));
        }

        private static String phoneticOf(JsonNode node) {
            String phonetic = HttpLookup.text(node, "phonetic");
            if (!phonetic.isEmpty()) {
                return phonetic;
            }
            JsonNode phonetics = node.get("phonetics");
            if (phonetics != null && phonetics.isArray()) {
                for (JsonNode candidate : phonetics) {
                    String text = candidate.isObject() ? HttpLookup.text(candidate, "text") : candidate.asText("").trim();
                    if (!text.isEmpty()) {
                        return text;
                    }
                }
            }
            return "";
        }

        private static String orElse(String value, String fallback) {
            return value.isEmpty() ? fallback : value;
        }
    }
}
