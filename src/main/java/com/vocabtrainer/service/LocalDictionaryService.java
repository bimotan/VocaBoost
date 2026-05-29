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
    private final LocalDictionaryStatus status;

    public LocalDictionaryService() {
        this(System.getenv("ECDICT_CSV_PATH"));
    }

    public LocalDictionaryService(String localCsvPath) {
        LoadOutcome outcome = loadEntries(localCsvPath);
        this.entries = outcome.entries();
        this.status = outcome.status();
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

    public LocalDictionaryStatus status() {
        return status;
    }

    private LoadOutcome loadEntries(String localCsvPath) {
        Map<String, DictionaryEntry> result = new LinkedHashMap<>();
        int skippedRows = 0;
        boolean configuredLoaded = false;
        boolean bundledLoaded = false;
        String primarySource = "";
        if (localCsvPath != null && !localCsvPath.isBlank()) {
            Path path = Path.of(localCsvPath.trim());
            if (Files.isRegularFile(path)) {
                try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    ReadStats stats = readCsv(reader, result, "ECDICT/local CSV");
                    skippedRows += stats.skippedRows();
                    configuredLoaded = stats.loadedRows() > 0;
                    if (configuredLoaded) {
                        primarySource = path.toAbsolutePath().toString();
                    }
                } catch (IOException ignored) {
                    // Fall back to bundled starter data.
                }
            }
        }
        var stream = LocalDictionaryService.class.getResourceAsStream(STARTER_RESOURCE);
        if (stream != null) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                ReadStats stats = readCsv(reader, result, "Bundled GRE starter");
                skippedRows += stats.skippedRows();
                bundledLoaded = stats.loadedRows() > 0;
                if (primarySource.isBlank() && bundledLoaded) {
                    primarySource = "Bundled GRE starter";
                }
            } catch (IOException ignored) {
                // A missing local dictionary should not stop the app.
            }
        }
        return new LoadOutcome(result, new LocalDictionaryStatus(
            result.size(),
            skippedRows,
            primarySource,
            localCsvPath == null ? "" : localCsvPath.trim(),
            configuredLoaded,
            bundledLoaded
        ));
    }

    private ReadStats readCsv(BufferedReader reader, Map<String, DictionaryEntry> target, String source)
        throws IOException {
        String line;
        int lineNumber = 0;
        int wordIndex = 0;
        int translationIndex = 1;
        int phoneticIndex = -1;
        int posIndex = 2;
        int exampleIndex = 3;
        int loadedRows = 0;
        int skippedRows = 0;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            List<String> fields = parseCsvLine(line);
            if (lineNumber == 1 && looksLikeHeader(fields)) {
                wordIndex = firstExistingIndex(fields, "word", "english");
                translationIndex = firstExistingIndex(fields, "translation", "chinese", "definition");
                phoneticIndex = firstExistingIndex(fields, "phonetic", "phonetics");
                posIndex = firstExistingIndex(fields, "pos", "part_of_speech", "partOfSpeech", "tag", "tags");
                exampleIndex = firstExistingIndex(fields, "example", "example_sentence", "sentence");
                continue;
            } else if (lineNumber == 1 && fields.size() > 3 && !fieldAt(fields, 3).isBlank()) {
                wordIndex = 0;
                phoneticIndex = 1;
                translationIndex = 3;
                posIndex = fields.size() > 4 ? 4 : -1;
                exampleIndex = -1;
            }
            if (wordIndex < 0 || translationIndex < 0 || fields.size() <= Math.max(wordIndex, translationIndex)) {
                skippedRows++;
                continue;
            }
            String english = fieldAt(fields, wordIndex);
            String chinese = fieldAt(fields, translationIndex);
            if (english.isBlank() || chinese.isBlank()) {
                skippedRows++;
                continue;
            }
            String key = normalizeKey(english);
            if (!target.containsKey(key)) {
                String pos = fieldAt(fields, posIndex);
                String example = fieldAt(fields, exampleIndex);
                String phonetic = fieldAt(fields, phoneticIndex);
                target.put(key, new DictionaryEntry(
                    english,
                    chinese,
                    pos,
                    phonetic,
                    example,
                    source
                ));
                loadedRows++;
            }
        }
        return new ReadStats(loadedRows, skippedRows);
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

    private boolean looksLikeHeader(List<String> fields) {
        return fields.stream()
            .map(value -> value.trim().toLowerCase(Locale.ROOT))
            .anyMatch(value -> value.equals("word") || value.equals("english") || value.equals("translation"));
    }

    private int firstExistingIndex(List<String> fields, String... names) {
        for (String name : names) {
            for (int i = 0; i < fields.size(); i++) {
                if (name.equalsIgnoreCase(fields.get(i).trim())) {
                    return i;
                }
            }
        }
        return -1;
    }

    private String fieldAt(List<String> fields, int index) {
        return index >= 0 && index < fields.size() ? fields.get(index).trim() : "";
    }

    private String normalizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private record LoadOutcome(Map<String, DictionaryEntry> entries, LocalDictionaryStatus status) {
    }

    private record ReadStats(int loadedRows, int skippedRows) {
    }
}
