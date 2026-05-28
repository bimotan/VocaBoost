package com.vocabtrainer.service;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PublicOnlineDictionaryService implements DictionaryService {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Pattern DEFINITION_PATTERN = Pattern.compile("\"definition\"\\s*:\\s*\"(.*?)\"", Pattern.DOTALL);
    private static final Pattern PHONETIC_PATTERN = Pattern.compile("\"phonetic\"\\s*:\\s*\"(.*?)\"", Pattern.DOTALL);
    private static final Pattern PART_OF_SPEECH_PATTERN = Pattern.compile("\"partOfSpeech\"\\s*:\\s*\"(.*?)\"", Pattern.DOTALL);

    private final HttpClient httpClient;

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
            List<String> definitions = values(DEFINITION_PATTERN, json);
            if (definitions.isEmpty()) {
                return DictionaryLookupResult.failure("dictionaryapi.dev did not return definitions.");
            }
            String phonetic = firstValue(PHONETIC_PATTERN, json);
            String pos = firstValue(PART_OF_SPEECH_PATTERN, json);
            List<DictionaryEntry> entries = definitions.stream()
                .limit(5)
                .map(definition -> new DictionaryEntry(
                    english,
                    "请填写中文释义（online: " + definition + "）",
                    pos,
                    phonetic,
                    "",
                    "dictionaryapi.dev"
                ))
                .toList();
            return DictionaryLookupResult.success("Loaded from dictionaryapi.dev. Please edit the Chinese meaning.", entries);
        } catch (Exception e) {
            return DictionaryLookupResult.failure("dictionaryapi.dev failed: " + e.getMessage());
        }
    }

    private DictionaryLookupResult lookupWiktionary(String english) {
        String encoded = URLEncoder.encode(english.replace(' ', '_'), StandardCharsets.UTF_8).replace("+", "%20");
        URI uri = URI.create("https://en.wiktionary.org/api/rest_v1/page/definition/" + encoded);
        try {
            String json = get(uri);
            List<String> definitions = values(DEFINITION_PATTERN, json);
            if (definitions.isEmpty()) {
                return DictionaryLookupResult.failure("Wiktionary did not return definitions.");
            }
            List<DictionaryEntry> entries = new ArrayList<>();
            for (String definition : definitions.stream().limit(5).toList()) {
                entries.add(new DictionaryEntry(
                    english,
                    "请填写中文释义（online: " + definition + "）",
                    "",
                    "",
                    "",
                    "Wiktionary"
                ));
            }
            return DictionaryLookupResult.success("Loaded from Wiktionary. Please edit the Chinese meaning.", entries);
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

    private List<String> values(Pattern pattern, String json) {
        List<String> result = new ArrayList<>();
        Matcher matcher = pattern.matcher(json == null ? "" : json);
        while (matcher.find()) {
            result.add(unescapeJson(matcher.group(1)).trim());
        }
        return result;
    }

    private String firstValue(Pattern pattern, String json) {
        Matcher matcher = pattern.matcher(json == null ? "" : json);
        return matcher.find() ? unescapeJson(matcher.group(1)).trim() : "";
    }

    private String unescapeJson(String value) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == '\\' && i + 1 < value.length()) {
                char next = value.charAt(++i);
                if (next == 'u' && i + 4 < value.length()) {
                    String hex = value.substring(i + 1, i + 5);
                    builder.append((char) Integer.parseInt(hex, 16));
                    i += 4;
                } else if (next == 'n') {
                    builder.append(' ');
                } else {
                    builder.append(next);
                }
            } else {
                builder.append(current);
            }
        }
        return builder.toString().replaceAll("\\s+", " ");
    }
}
