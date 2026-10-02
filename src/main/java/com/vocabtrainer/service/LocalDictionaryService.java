package com.vocabtrainer.service;

import com.vocabtrainer.domain.DictionaryEntry;
import com.vocabtrainer.domain.DictionaryLookupResult;
import com.vocabtrainer.domain.WordVerificationResult;
import com.vocabtrainer.service.csv.CsvReader;
import com.vocabtrainer.service.csv.CsvRecord;
import com.vocabtrainer.service.csv.WordColumn;
import com.vocabtrainer.service.csv.WordColumns;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

public class LocalDictionaryService implements DictionaryService {
    private static final Logger LOGGER = Logger.getLogger(LocalDictionaryService.class.getName());
    private static final String STARTER_RESOURCE = "/data/gre_starter_sample.csv";
    private static final WordColumns STARTER_COLUMNS_BY_POSITION = WordColumns.positional(
        WordColumn.ENGLISH, WordColumn.CHINESE, WordColumn.POS, WordColumn.EXAMPLE);
    private static final WordColumns ECDICT_COLUMNS_BY_POSITION = WordColumns.positional(
        WordColumn.ENGLISH, WordColumn.PHONETIC, null, WordColumn.CHINESE, WordColumn.POS);

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
                try (CsvReader reader = CsvReader.open(path)) {
                    ReadStats stats = readCsv(reader, result, "ECDICT/local CSV");
                    skippedRows += stats.skippedRows();
                    configuredLoaded = stats.loadedRows() > 0;
                    if (configuredLoaded) {
                        primarySource = path.toAbsolutePath().toString();
                    }
                } catch (IOException e) {
                    // Fall back to bundled starter data.
                    LOGGER.log(Level.WARNING, "Cannot read local dictionary CSV " + path.toAbsolutePath()
                        + "; using the bundled starter dictionary instead", e);
                }
            }
        }
        InputStream stream = LocalDictionaryService.class.getResourceAsStream(STARTER_RESOURCE);
        if (stream != null) {
            try (InputStream in = stream; CsvReader reader = CsvReader.open(in, StandardCharsets.UTF_8)) {
                ReadStats stats = readCsv(reader, result, "Bundled GRE starter");
                skippedRows += stats.skippedRows();
                bundledLoaded = stats.loadedRows() > 0;
                if (primarySource.isBlank() && bundledLoaded) {
                    primarySource = "Bundled GRE starter";
                }
            } catch (IOException e) {
                // A missing local dictionary should not stop the app.
                LOGGER.log(Level.WARNING, "Cannot read bundled starter dictionary " + STARTER_RESOURCE, e);
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

    /**
     * Reads entries by header names (see {@link WordColumn}). Without a header row the columns are
     * english, chinese, pos, example, like the starter CSV, unless the first row looks like ECDICT's
     * own order (word, phonetic, definition, translation, pos, ...): five or more fields with Chinese
     * in the fourth and none in the second.
     */
    private ReadStats readCsv(CsvReader reader, Map<String, DictionaryEntry> target, String source)
        throws IOException {
        int loadedRows = 0;
        int skippedRows = 0;
        WordColumns columns = null;
        CsvRecord record;
        while ((record = reader.read()) != null) {
            if (record.isBlank()) {
                continue;
            }
            if (columns == null) {
                Optional<WordColumns> header = WordColumns.fromHeader(record);
                if (header.isPresent()) {
                    columns = header.get();
                    continue;
                }
                columns = looksLikeHeaderlessEcdict(record) ? ECDICT_COLUMNS_BY_POSITION : STARTER_COLUMNS_BY_POSITION;
            }
            String english = columns.get(record, WordColumn.ENGLISH).trim();
            String chinese = columns.get(record, WordColumn.CHINESE).trim();
            if (english.isBlank() || chinese.isBlank()) {
                skippedRows++;
                continue;
            }
            String key = normalizeKey(english);
            if (!target.containsKey(key)) {
                target.put(key, new DictionaryEntry(
                    english,
                    chinese,
                    columns.get(record, WordColumn.POS).trim(),
                    columns.get(record, WordColumn.PHONETIC).trim(),
                    columns.get(record, WordColumn.EXAMPLE).trim(),
                    source
                ));
                loadedRows++;
            }
        }
        return new ReadStats(loadedRows, skippedRows);
    }

    private static boolean looksLikeHeaderlessEcdict(CsvRecord record) {
        return record.size() >= 5 && containsCjk(record.get(3)) && !containsCjk(record.get(1));
    }

    private static boolean containsCjk(String value) {
        return value.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    private String normalizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private record LoadOutcome(Map<String, DictionaryEntry> entries, LocalDictionaryStatus status) {
    }

    private record ReadStats(int loadedRows, int skippedRows) {
    }
}
