package com.vocabtrainer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vocabtrainer.domain.DictionaryEntry;
import com.vocabtrainer.domain.DictionaryLookupResult;
import com.vocabtrainer.domain.WordVerificationResult;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class PublicOnlineDictionaryService implements DictionaryService {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PublicOnlineDictionaryService() {
        this(HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    }

    public PublicOnlineDictionaryService(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public DictionaryLookupResult lookup(String english) {
        String clean = english == null ? "" : english.trim();
        if (clean.isBlank()) {
            return DictionaryLookupResult.failure("Please enter an English word first.");
        }
        DictionaryLookupResult dictionaryApi = lookupDictionaryApi(clean);
        if (dictionaryApi.success()) {
            return dictionaryApi;
        }
        DictionaryLookupResult wiktionary = lookupWiktionary(clean);
        if (wiktionary.success()) {
            return wiktionary;
        }
        return DictionaryLookupResult.failure("词条未找到：本地词库和在线词典都没有返回可用结果。");
    }

    @Override
    public WordVerificationResult verify(String english) {
        DictionaryLookupResult result = lookup(english);
        if (result.success()) {
            return WordVerificationResult.found(result.entries().get(0).source(), "在线词典已验证该词条。");
        }
        return WordVerificationResult.missing(result.message());
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    private DictionaryLookupResult lookupDictionaryApi(String english) {
        String encoded = URLEncoder.encode(english, StandardCharsets.UTF_8).replace("+", "%20");
        URI uri = URI.create("https://api.dictionaryapi.dev/api/v2/entries/en/" + encoded);
        try {
            String json = get(uri);
            JsonNode root = objectMapper.readTree(json);
            List<DictionaryEntry> entries = parseDictionaryApi(english, root);
            if (entries.isEmpty()) {
                return DictionaryLookupResult.failure("dictionaryapi.dev did not return definitions.");
            }
            return DictionaryLookupResult.success("Loaded from dictionaryapi.dev. 该来源主要返回英文释义，请确认或填写中文释义。", entries);
        } catch (Exception e) {
            return DictionaryLookupResult.failure("dictionaryapi.dev failed: " + e.getMessage());
        }
    }

    private DictionaryLookupResult lookupWiktionary(String english) {
        String encoded = URLEncoder.encode(english.replace(' ', '_'), StandardCharsets.UTF_8).replace("+", "%20");
        URI uri = URI.create("https://en.wiktionary.org/api/rest_v1/page/definition/" + encoded);
        try {
            String json = get(uri);
            JsonNode root = objectMapper.readTree(json);
            List<DictionaryEntry> entries = parseWiktionary(english, root);
            if (entries.isEmpty()) {
                return DictionaryLookupResult.failure("Wiktionary did not return definitions.");
            }
            return DictionaryLookupResult.success("Loaded from Wiktionary. 该来源主要返回英文释义，请确认或填写中文释义。", entries);
        } catch (Exception e) {
            return DictionaryLookupResult.failure("Wiktionary failed: " + e.getMessage());
        }
    }

    private String get(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(TIMEOUT)
            .header("User-Agent", "VocaBoost/1.0")
            .GET()
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return response.body();
    }

    private List<DictionaryEntry> parseDictionaryApi(String english, JsonNode root) {
        List<DictionaryEntry> entries = new ArrayList<>();
        if (!root.isArray()) {
            return entries;
        }
        for (JsonNode wordNode : root) {
            String phonetic = text(wordNode, "phonetic");
            for (JsonNode meaning : wordNode.path("meanings")) {
                String pos = text(meaning, "partOfSpeech");
                for (JsonNode definitionNode : meaning.path("definitions")) {
                    String definition = text(definitionNode, "definition");
                    if (!definition.isBlank()) {
                        entries.add(new DictionaryEntry(
                            english,
                            "请填写中文释义（English definition: " + definition + "）",
                            pos,
                            phonetic,
                            text(definitionNode, "example"),
                            "dictionaryapi.dev"
                        ));
                    }
                    if (entries.size() >= 5) {
                        return entries;
                    }
                }
            }
        }
        return entries;
    }

    private List<DictionaryEntry> parseWiktionary(String english, JsonNode root) {
        List<DictionaryEntry> entries = new ArrayList<>();
        collectWiktionaryEntries(english, root, "", entries);
        return entries.size() > 5 ? entries.subList(0, 5) : entries;
    }

    private void collectWiktionaryEntries(String english, JsonNode node, String pos, List<DictionaryEntry> entries) {
        if (node == null || entries.size() >= 5) {
            return;
        }
        String nextPos = text(node, "partOfSpeech");
        if (nextPos.isBlank()) {
            nextPos = pos;
        }
        String definition = text(node, "definition");
        if (!definition.isBlank()) {
            entries.add(new DictionaryEntry(
                english,
                "请填写中文释义（English definition: " + definition + "）",
                nextPos,
                "",
                "",
                "Wiktionary"
            ));
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collectWiktionaryEntries(english, child, nextPos, entries);
            }
        } else if (node.isObject()) {
            for (JsonNode child : node) {
                collectWiktionaryEntries(english, child, nextPos, entries);
            }
        }
    }

    private String text(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return "";
        }
        return node.get(field).asText("").replaceAll("\\s+", " ").trim();
    }
}
