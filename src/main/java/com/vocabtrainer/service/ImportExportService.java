package com.vocabtrainer.service;

import com.vocabtrainer.domain.ValidatedWord;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.service.csv.CsvReader;
import com.vocabtrainer.service.csv.CsvRecord;
import com.vocabtrainer.service.csv.TextEncoding;
import com.vocabtrainer.service.csv.WordColumn;
import com.vocabtrainer.service.csv.WordColumns;
import com.vocabtrainer.util.DateTimeUtil;
import com.vocabtrainer.util.ErrorMessages;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Imports word lists into a deck: GRE CSV files (read with {@link CsvReader}, so UTF-8 or GBK, any
 * of comma, tab or semicolon, columns found by header names), the bundled GRE starter words and the
 * legacy console app's txt files.
 */
public class ImportExportService {
    private static final String GRE_STARTER_RESOURCE = "/data/gre_starter_sample.csv";
    private static final int MAX_GRE_IMPORT_WORDS = 2000;
    /** Row messages beyond this many are only counted, so a large broken file cannot flood the UI. */
    static final int MAX_LISTED_MESSAGES = 100;
    /** The column order of a GRE CSV without a header row. */
    private static final WordColumns GRE_COLUMNS_BY_POSITION = WordColumns.positional(
        WordColumn.ENGLISH, WordColumn.CHINESE, WordColumn.POS, WordColumn.EXAMPLE, WordColumn.TAGS);
    /** A line break in a meaning cell (Alt+Enter in Excel) separates two meanings. */
    private static final Pattern LINE_BREAKS = Pattern.compile("\\s*\\R\\s*");

    private final WordRepository wordRepository;
    private final WordValidationService validationService;

    public ImportExportService(WordRepository wordRepository) {
        this(wordRepository, new WordValidationService());
    }

    public ImportExportService(WordRepository wordRepository, WordValidationService validationService) {
        this.wordRepository = wordRepository;
        this.validationService = validationService;
    }

    public ImportResult importLegacyTxt(Path path, long deckId) {
        try {
            TextEncoding encoding = TextEncoding.detect(path);
            try (BufferedReader reader = new BufferedReader(encoding.openReader(path))) {
                return importLegacyTxt(reader, encoding, deckId);
            }
        } catch (IOException e) {
            throw new IllegalStateException(cannotRead("import file", path, e), e);
        }
    }

    public ImportResult importGreCsv(Path path, long deckId) {
        try (CsvReader reader = CsvReader.open(path)) {
            return importGreCsv(reader, deckId);
        } catch (IOException e) {
            throw new IllegalStateException(cannotRead("GRE CSV file", path, e), e);
        }
    }

    public ImportPreview previewGreCsv(Path path, long deckId) {
        try (CsvReader reader = CsvReader.open(path)) {
            GreCsvAnalysis analysis = analyzeGreCsv(reader, deckId);
            return new ImportPreview(
                analysis.totalRows,
                analysis.words.size(),
                analysis.duplicates,
                analysis.skipped - analysis.duplicates,
                analysis.messages.stream().limit(10).toList(),
                reader.encoding().map(TextEncoding::displayName).orElse("decoded text"),
                reader.delimiterName(),
                analysis.columns.describe() + (analysis.columns.fromHeader() ? " (header row)" : " (by position, no header row)")
            );
        } catch (IOException e) {
            throw new IllegalStateException(cannotRead("GRE CSV file", path, e), e);
        }
    }

    public ImportResult importBundledGreStarter(long deckId) {
        InputStream stream = ImportExportService.class.getResourceAsStream(GRE_STARTER_RESOURCE);
        if (stream == null) {
            throw new IllegalStateException("Bundled GRE starter deck is missing: " + GRE_STARTER_RESOURCE);
        }
        try (InputStream in = stream; CsvReader reader = CsvReader.open(in, StandardCharsets.UTF_8)) {
            return importGreCsv(reader, deckId);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read bundled GRE starter deck: " + reason(e), e);
        }
    }

    private ImportResult importLegacyTxt(BufferedReader reader, TextEncoding encoding, long deckId) throws IOException {
        int imported = 0;
        int skipped = 0;
        RowMessages messages = new RowMessages();
        Set<String> known = existingEnglishKeys(deckId);
        int lineNumber = 0;
        while (true) {
            String line;
            try {
                line = reader.readLine();
            } catch (CharacterCodingException e) {
                throw new IOException("Line " + (lineNumber + 1) + ": the text is not valid " + encoding.displayName()
                    + " (save the file as UTF-8 and try again)", e);
            }
            if (line == null) {
                break;
            }
            lineNumber++;
            if (lineNumber == 1 && line.startsWith("\uFEFF")) {
                line = line.substring(1);
            }
            if (line.isBlank()) {
                continue;
            }
            try {
                WordCard card = parseLegacyLine(line, deckId);
                if (known.contains(key(card.getEnglish()))) {
                    skipped++;
                    messages.add("Line " + lineNumber + " skipped: duplicate word " + card.getEnglish());
                    continue;
                }
                wordRepository.insert(card);
                known.add(key(card.getEnglish()));
                imported++;
            } catch (IllegalArgumentException | SQLException e) {
                skipped++;
                messages.add("Line " + lineNumber + " skipped: " + e.getMessage());
            }
        }
        return new ImportResult(imported, skipped, messages.toList());
    }

    private ImportResult importGreCsv(CsvReader reader, long deckId) throws IOException {
        GreCsvAnalysis analysis = analyzeGreCsv(reader, deckId);
        try {
            int imported = wordRepository.insertAll(analysis.words);
            return new ImportResult(imported, analysis.skipped, analysis.messages);
        } catch (SQLException e) {
            throw new IllegalStateException("GRE CSV transaction failed: " + ErrorMessages.rootMessage(e), e);
        }
    }

    /**
     * Reads the whole file and decides, row by row, what an import would do. Nothing is written.
     * Messages name the line each row starts on, which differs from the row count when a quoted
     * cell holds line breaks.
     */
    private GreCsvAnalysis analyzeGreCsv(CsvReader reader, long deckId) throws IOException {
        int imported = 0;
        int skipped = 0;
        int duplicates = 0;
        int totalRows = 0;
        boolean stoppedAtLimit = false;
        RowMessages messages = new RowMessages();
        List<WordCard> pendingWords = new ArrayList<>();
        // The deck's words and the rows read so far, by lower-case English word.
        Set<String> seen = existingEnglishKeys(deckId);

        CsvRecord record = nextNonBlank(reader);
        WordColumns columns = GRE_COLUMNS_BY_POSITION;
        Optional<WordColumns> header = record == null ? Optional.empty() : WordColumns.fromHeader(record);
        if (header.isPresent()) {
            columns = header.get();
            requireColumn(columns, WordColumn.ENGLISH, record);
            requireColumn(columns, WordColumn.CHINESE, record);
            record = reader.read();
        }
        int requiredFields = Math.max(columns.index(WordColumn.ENGLISH), columns.index(WordColumn.CHINESE)) + 1;
        for (; record != null; record = reader.read()) {
            if (record.isBlank()) {
                continue;
            }
            totalRows++;
            if (imported >= MAX_GRE_IMPORT_WORDS) {
                stoppedAtLimit = true;
                break;
            }
            try {
                if (record.size() < requiredFields) {
                    throw new IllegalArgumentException("CSV row must contain english and chinese");
                }
                ValidatedWord validated = validationService.validate(
                    columns.get(record, WordColumn.ENGLISH),
                    LINE_BREAKS.matcher(columns.get(record, WordColumn.CHINESE).strip()).replaceAll("; "),
                    columns.get(record, WordColumn.PHONETIC),
                    columns.get(record, WordColumn.POS),
                    columns.get(record, WordColumn.EXAMPLE),
                    columns.get(record, WordColumn.NOTE),
                    columns.get(record, WordColumn.TAGS)
                );
                if (!seen.add(key(validated.english()))) {
                    skipped++;
                    duplicates++;
                    messages.add("Line " + record.lineNumber() + " skipped: duplicate word " + validated.english());
                    continue;
                }
                WordCard word = WordCard.createNew(deckId, validated.english(), validated.chinese());
                word.setPhonetic(validated.phonetic());
                word.setPartOfSpeech(validated.partOfSpeech());
                word.setExampleSentence(validated.exampleSentence());
                word.setNote(validated.note());
                word.setTags(validated.tags());
                pendingWords.add(word);
                imported++;
            } catch (IllegalArgumentException e) {
                skipped++;
                messages.add("Line " + record.lineNumber() + " skipped: " + e.getMessage());
            }
        }
        List<String> messageList = new ArrayList<>(messages.toList());
        if (stoppedAtLimit) {
            messageList.add("GRE import stopped at " + MAX_GRE_IMPORT_WORDS + " imported words.");
        }
        return new GreCsvAnalysis(totalRows, skipped, duplicates, messageList, pendingWords, columns);
    }

    private static CsvRecord nextNonBlank(CsvReader reader) throws IOException {
        CsvRecord record = reader.read();
        while (record != null && record.isBlank()) {
            record = reader.read();
        }
        return record;
    }

    private static void requireColumn(WordColumns columns, WordColumn column, CsvRecord header) {
        if (!columns.has(column)) {
            throw new IllegalArgumentException("The header row (line " + header.lineNumber() + ") has no "
                + column.headerName() + " column. Name it one of: " + String.join(", ", column.aliases()) + ".");
        }
    }

    private Set<String> existingEnglishKeys(long deckId) {
        try {
            return wordRepository.findEnglishKeys(deckId);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read the deck's words: " + ErrorMessages.rootMessage(e), e);
        }
    }

    private static String key(String english) {
        return english.trim().toLowerCase(Locale.ROOT);
    }

    private static String cannotRead(String what, Path path, IOException e) {
        return "Cannot read " + what + " " + path + ": " + reason(e);
    }

    private static String reason(IOException e) {
        if (e instanceof NoSuchFileException) {
            return "the file does not exist";
        }
        if (e instanceof AccessDeniedException) {
            return "access to the file was denied";
        }
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    /** Lists the first {@link #MAX_LISTED_MESSAGES} row messages and counts the rest. */
    private static final class RowMessages {
        private final List<String> listed = new ArrayList<>();
        private int unlisted;

        void add(String message) {
            if (listed.size() < MAX_LISTED_MESSAGES) {
                listed.add(message);
            } else {
                unlisted++;
            }
        }

        List<String> toList() {
            if (unlisted == 0) {
                return listed;
            }
            List<String> all = new ArrayList<>(listed);
            all.add("... and " + unlisted + " more rows skipped.");
            return all;
        }
    }

    private record GreCsvAnalysis(
        int totalRows,
        int skipped,
        int duplicates,
        List<String> messages,
        List<WordCard> words,
        WordColumns columns
    ) {
    }

    private WordCard parseLegacyLine(String line, long deckId) {
        String[] parts = line.split(";", -1);
        if (parts.length != 7) {
            throw new IllegalArgumentException("field count is not 7");
        }
        ValidatedWord validated = validationService.validate(parts[0], parts[1]);
        try {
            LocalDateTime addedAt = LocalDateTime.parse(parts[2].trim(), DateTimeUtil.LEGACY_FORMATTER);
            LocalDateTime lastReviewedAt = LocalDateTime.parse(parts[3].trim(), DateTimeUtil.LEGACY_FORMATTER);
            double easiness = Double.parseDouble(parts[4].trim());
            int intervalDays = Integer.parseInt(parts[5].trim());
            int consecutiveCorrect = Integer.parseInt(parts[6].trim());

            WordCard card = WordCard.createNew(deckId, validated.english(), validated.chinese());
            card.setAddedAt(addedAt);
            card.setLastReviewedAt(lastReviewedAt);
            card.setEasinessFactor(Math.max(1.3, easiness));
            card.setIntervalDays(Math.max(0, intervalDays));
            card.setConsecutiveCorrect(Math.max(0, consecutiveCorrect));
            card.setRepetitions(Math.max(0, consecutiveCorrect));
            card.setNextReviewAt(intervalDays <= 0 ? LocalDateTime.now() : lastReviewedAt.plusDays(intervalDays));
            return card;
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("date format is invalid (" + e.getMessage() + ")", e);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("review parameters must be numeric (" + e.getMessage() + ")", e);
        }
    }
}
