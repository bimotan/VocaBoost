package com.vocabtrainer.service;

import com.vocabtrainer.domain.DictionaryEntry;
import com.vocabtrainer.domain.DictionaryLookupResult;

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

public class HttpDictionaryService implements DictionaryService {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Pattern OBJECT_PATTERN = Pattern.compile("\\{[^{}]*}", Pattern.DOTALL);

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient httpClient;

    public HttpDictionaryService(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    }

    public HttpDictionaryService(String baseUrl, String apiKey, HttpClient httpClient) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.httpClient = httpClient;
    }

    @Override
    public DictionaryLookupResult lookup(String english) {
        if (!isConfigured()) {
            return DictionaryLookupResult.failure("DICTIONARY_API_BASE_URL is not configured.");
        }
        String clean = english == null ? "" : english.trim();
        if (clean.isBlank()) {
            return DictionaryLookupResult.failure("Please enter an English word first.");
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(buildUri(clean))
                .timeout(TIMEOUT)
                .GET();
            if (!apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
                builder.header("X-API-Key", apiKey);
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return DictionaryLookupResult.failure("Dictionary API returned HTTP " + response.statusCode() + ".");
            }
            List<DictionaryEntry> entries = parseEntries(clean, response.body());
            if (entries.isEmpty()) {
                return DictionaryLookupResult.failure("Dictionary API response did not contain usable Chinese meanings.");
            }
            return DictionaryLookupResult.success("Loaded from configured dictionary API.", entries);
        } catch (IOException e) {
            return DictionaryLookupResult.failure("Network error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return DictionaryLookupResult.failure("Dictionary lookup was interrupted.");
        } catch (IllegalArgumentException e) {
            return DictionaryLookupResult.failure("Dictionary API URL is invalid.");
        }
    }

    @Override
    public boolean isConfigured() {
        return !baseUrl.isBlank();
    }

    private URI buildUri(String english) {
        String separator = baseUrl.contains("?") ? "&" : "?";
        return URI.create(baseUrl + separator + "word=" + URLEncoder.encode(english, StandardCharsets.UTF_8));
    }

    private List<DictionaryEntry> parseEntries(String english, String json) {
        List<DictionaryEntry> entries = new ArrayList<>();
        Matcher matcher = OBJECT_PATTERN.matcher(json == null ? "" : json);
        while (matcher.find()) {
            DictionaryEntry entry = parseObject(english, matcher.group());
            if (entry != null) {
                entries.add(entry);
            }
        }
        if (entries.isEmpty()) {
            DictionaryEntry entry = parseObject(english, json == null ? "" : json);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
    }

    private DictionaryEntry parseObject(String english, String objectJson) {
        String chinese = firstValue(objectJson, "chinese", "translation", "meaning", "definition");
        if (chinese.isBlank()) {
            return null;
        }
        return new DictionaryEntry(
            firstNonBlank(firstValue(objectJson, "english", "word"), english),
            chinese,
            firstValue(objectJson, "partOfSpeech", "pos"),
            firstValue(objectJson, "phonetic", "phonetics"),
            firstValue(objectJson, "example", "exampleSentence"),
            firstNonBlank(firstValue(objectJson, "source"), "Configured API")
        );
    }

    private String firstValue(String json, String... keys) {
        for (String key : keys) {
            Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"(.*?)\"", Pattern.DOTALL)
                .matcher(json);
            if (matcher.find()) {
                return unescapeJson(matcher.group(1)).trim();
            }
        }
        return "";
    }

    private String firstNonBlank(String left, String right) {
        return left == null || left.isBlank() ? right : left;
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
                    builder.append('\n');
                } else if (next == 't') {
                    builder.append('\t');
                } else {
                    builder.append(next);
                }
            } else {
                builder.append(current);
            }
        }
        return builder.toString();
    }
}
