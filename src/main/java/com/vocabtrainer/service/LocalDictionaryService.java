package com.vocabtrainer.service;

import com.vocabtrainer.domain.DictionaryEntry;
import com.vocabtrainer.domain.DictionaryLookupResult;
import com.vocabtrainer.domain.WordVerificationResult;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class LocalDictionaryService implements DictionaryService {
    private static final String STARTER_RESOURCE = "/data/gre_starter_sample.csv";

    private final Map<String, DictionaryEntry> entries;

    public LocalDictionaryService() {
        this(System.getenv("ECDICT_CSV_PATH"));
    }

    public LocalDictionaryService(String localCsvPath) {
        this.entries = loadEntries(localCsvPath);
    }

    @Override
    public DictionaryLookupResult lookup(String english) {
        String key = normalizeKey(english);
        if (key.isBlank()) {
            return DictionaryLookupResult.failure("Please enter an English word first.");
        }
        DictionaryEntry entry = entries.get(key);
        if (entry == null) {
            return DictionaryLookupResult.failure("词条未找到：本地词库没有该词条。");
        }
        return DictionaryLookupResult.success("Loaded from local dictionary.", List.of(entry));
    }

    @Override
    public WordVerificationResult verify(String english) {
        String key = normalizeKey(english);
        if (entries.containsKey(key)) {
            return WordVerificationResult.found("Local dictionary", "本地词库已验证该词条。");
        }
        return WordVerificationResult.missing("词条未找到：本地词库没有该词条。");
    }

    @Override
    public boolean isConfigured() {
        return !entries.isEmpty();
    }

    private Map<String, DictionaryEntry> loadEntries(String localCsvPath) {
        Map<String, DictionaryEntry> result = new LinkedHashMap<>();
        if (localCsvPath != null && !localCsvPath.isBlank()) {
            Path path = Path.of(localCsvPath.trim());
            if (Files.isRegularFile(path)) {
                try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    readCsv(reader, result, "ECDICT/local CSV");
                } catch (IOException ignored) {
                    // Fall back to bundled starter data.
                }
            }
        }
        var stream = LocalDictionaryService.class.getResourceAsStream(STARTER_RESOURCE);
        if (stream != null) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                readCsv(reader, result, "Bundled GRE starter");
            } catch (IOException ignored) {
                // A missing local dictionary should not stop the app.
            }
        }
        return result;
    }

    private void readCsv(BufferedReader reader, Map<String, DictionaryEntry> target, String source)
        throws IOException {
        String line;
        int lineNumber = 0;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            List<String> fields = parseCsvLine(line);
            if (lineNumber == 1 && !fields.isEmpty()
                && ("english".equalsIgnoreCase(fields.get(0).trim()) || "word".equalsIgnoreCase(fields.get(0).trim()))) {
                continue;
            }
            if (fields.size() < 2) {
                continue;
            }
            String english = fields.get(0).trim();
            String chinese = fields.get(1).trim();
            if (english.isBlank() || chinese.isBlank()) {
                continue;
            }
            String pos = fields.size() > 2 ? fields.get(2).trim() : "";
            String example = fields.size() > 3 ? fields.get(3).trim() : "";
            target.putIfAbsent(normalizeKey(english), new DictionaryEntry(
                english,
                chinese,
                pos,
                "",
                example,
                source
            ));
        }
    }

    private List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ',' && !quoted) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }

    private String normalizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
