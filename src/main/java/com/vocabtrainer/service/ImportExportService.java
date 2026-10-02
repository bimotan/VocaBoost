package com.vocabtrainer.service;

import com.vocabtrainer.domain.DictionaryEntry;
import com.vocabtrainer.domain.DictionaryLookupResult;
import com.vocabtrainer.domain.LookupOutcome;
import com.vocabtrainer.domain.ValidatedWord;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.service.csv.CsvRecord;
import com.vocabtrainer.service.csv.CsvWriter;
import com.vocabtrainer.service.csv.TextEncoding;
import com.vocabtrainer.service.csv.WordColumn;
import com.vocabtrainer.service.csv.WordColumns;
import com.vocabtrainer.service.wordlist.AnkiExport;
import com.vocabtrainer.service.wordlist.HtmlText;
import com.vocabtrainer.service.wordlist.WordListFile;
import com.vocabtrainer.util.DateTimeUtil;
import com.vocabtrainer.util.ErrorMessages;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Imports word lists into a deck and exports a deck's words for other apps.
 *
 * <ul>
 *   <li>Word lists ({@link WordListFile}): GRE CSV and TSV files (UTF-8 or GBK, comma, tab or
 *       semicolon, columns by header names or detected from the content), Anki plain-text exports
 *       (header lines, HTML fields, a tags column) and lists of English words, one per line. The
 *       user can choose the columns in the preview ({@link WordListOptions}). A row without a Chinese
 *       meaning gets the meaning, phonetic and part of speech from the local dictionaries (imported
 *       ECDICT, bundled starter words), and online only when the user allows it and offline mode is
 *       off; a word no dictionary has is skipped with the reason, since a word needs a meaning.</li>
 *   <li>The bundled GRE starter words and the legacy console app's txt files.</li>
 *   <li>"Export for Anki" ({@link AnkiExport}) and a plain list of the deck's English words.</li>
 * </ul>
 */
public class ImportExportService {
    private static final String GRE_STARTER_RESOURCE = "/data/gre_starter_sample.csv";
    private static final int MAX_GRE_IMPORT_WORDS = 2000;
    /** Row messages beyond this many are only counted, so a large broken file cannot flood the UI. */
    static final int MAX_LISTED_MESSAGES = 100;
    /** Rows the preview shows as they would be imported. */
    static final int PREVIEW_ROWS = 8;
    private static final String LOCAL_DICTIONARY = "the local dictionary";
    private static final String ALL_DICTIONARIES = "the local and online dictionaries";
    /** What the preview says will happen to a row. */
    private static final String NEEDS_MEANING = "Needs a meaning";
    private static final String IMPORT = "Import";

    private final WordRepository wordRepository;
    private final WordValidationService validationService;
    private final DictionaryService localDictionary;
    private final Supplier<DictionaryService> onlineDictionary;
    private final BooleanSupplier offline;

    /** Without dictionaries: a row without a meaning is skipped. */
    public ImportExportService(WordRepository wordRepository) {
        this(wordRepository, new WordValidationService());
    }

    /** Without dictionaries: a row without a meaning is skipped. */
    public ImportExportService(WordRepository wordRepository, WordValidationService validationService) {
        this(wordRepository, validationService, null, null, () -> true);
    }

    /**
     * @param localDictionary  fills in the meaning of a word that a word list has without one; never
     *                         asks the network (null: such rows are skipped)
     * @param onlineDictionary the whole dictionary chain, asked instead when the user allows online
     *                         lookups for an import and {@code offline} answers false; built only then
     *                         (null: never)
     * @param offline          whether offline mode is on, read when an import starts
     */
    public ImportExportService(WordRepository wordRepository, WordValidationService validationService,
                               DictionaryService localDictionary, Supplier<DictionaryService> onlineDictionary,
                               BooleanSupplier offline) {
        this.wordRepository = wordRepository;
        this.validationService = validationService;
        this.localDictionary = localDictionary;
        this.onlineDictionary = onlineDictionary;
        this.offline = offline;
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

    /** Imports a word list with the columns it names or that its content suggests, without online lookups. */
    public ImportResult importGreCsv(Path path, long deckId) {
        return importWordList(path, deckId, WordListOptions.DETECT, progress -> { }, () -> false);
    }

    public ImportPreview previewGreCsv(Path path, long deckId) {
        return previewWordList(path, deckId, WordListOptions.DETECT);
    }

    /**
     * Reads the whole file and says what an import with {@code options} would do; nothing is written
     * and nothing is looked up online. The first rows that have no meaning are looked up in the
     * local dictionary, to show what the import would fill in.
     */
    public ImportPreview previewWordList(Path path, long deckId, WordListOptions options) {
        try (WordListFile file = WordListFile.open(path)) {
            Analysis analysis = analyze(file, deckId, options, canFill(options), () -> false);
            return new ImportPreview(
                analysis.totalRows,
                analysis.readyCount(),
                analysis.duplicates,
                analysis.skipped - analysis.duplicates,
                analysis.pendingCount(),
                analysis.messages.toList().stream().limit(10).toList(),
                file.encodingName(),
                file.delimiterName(),
                analysis.columns.describe() + analysis.layout,
                file.anki().describe(),
                analysis.columns,
                file.columns(),
                previewRows(analysis, options)
            );
        } catch (IOException e) {
            throw new IllegalStateException(cannotRead("word list", path, e), e);
        }
    }

    /**
     * Imports a word list with the columns in {@code options} (detected when it has none). Rows
     * without a meaning are looked up first, reporting {@code progress}; nothing is written until
     * every lookup is done, and then every word in one transaction. When {@code cancelled} answers
     * true or the thread is interrupted, the import stops with a {@link CancellationException} and
     * nothing is imported.
     */
    public ImportResult importWordList(Path path, long deckId, WordListOptions options,
                                       Consumer<ImportProgress> progress, BooleanSupplier cancelled) {
        try (WordListFile file = WordListFile.open(path)) {
            return importWordList(file, deckId, options, progress, cancelled);
        } catch (IOException e) {
            throw new IllegalStateException(cannotRead("word list", path, e), e);
        }
    }

    public ImportResult importBundledGreStarter(long deckId) {
        InputStream stream = ImportExportService.class.getResourceAsStream(GRE_STARTER_RESOURCE);
        if (stream == null) {
            throw new IllegalStateException("Bundled GRE starter deck is missing: " + GRE_STARTER_RESOURCE);
        }
        try (InputStream in = stream; WordListFile file = WordListFile.open(in, StandardCharsets.UTF_8)) {
            return importWordList(file, deckId, WordListOptions.DETECT, progress -> { }, () -> false);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read bundled GRE starter deck: " + reason(e), e);
        }
    }

    /**
     * Writes the deck's words, suspended ones included (as the CSV export does), as an Anki plain-text
     * file: UTF-8, tab-separated, with the header lines of {@link AnkiExport#HEADER}, one note per word
     * with the English word, the meaning (with the part of speech and phonetic on a second line), the
     * example and the tags.
     */
    public Path exportForAnki(long deckId, Path output) {
        try {
            List<WordCard> words = wordRepository.findAllIncludingSuspended(deckId);
            createParent(output);
            try (Writer writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
                writer.write(AnkiExport.HEADER);
                CsvWriter rows = CsvWriter.plainText(writer, AnkiExport.SEPARATOR, AnkiExport.LINE_END);
                for (WordCard word : words) {
                    rows.writeRow(
                        HtmlText.escape(word.getEnglish()),
                        AnkiExport.meaningField(word.getChinese(), word.getPartOfSpeech(), word.getPhonetic()),
                        HtmlText.escape(word.getExampleSentence()),
                        AnkiExport.tagsField(word.getTags()));
                }
            }
            return output;
        } catch (IOException e) {
            throw new IllegalStateException(cannotWrite("Anki export", output, e), e);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read the deck's words: " + ErrorMessages.rootMessage(e), e);
        }
    }

    /**
     * Writes the deck's English words, suspended ones included, one per line (UTF-8, CRLF), for apps
     * that take a plain word list.
     */
    public Path exportWordList(long deckId, Path output) {
        try {
            List<WordCard> words = wordRepository.findAllIncludingSuspended(deckId);
            createParent(output);
            try (Writer writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
                for (WordCard word : words) {
                    writer.write(word.getEnglish());
                    writer.write("\r\n");
                }
            }
            return output;
        } catch (IOException e) {
            throw new IllegalStateException(cannotWrite("word list", output, e), e);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read the deck's words: " + ErrorMessages.rootMessage(e), e);
        }
    }

    private static void createParent(Path output) throws IOException {
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
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
                throw new IOException("Line " + (lineNumber + 1) + ": " + encoding.undecodableMessage(), e);
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

    private ImportResult importWordList(WordListFile file, long deckId, WordListOptions options,
                                        Consumer<ImportProgress> progress, BooleanSupplier cancelled)
        throws IOException {
        DictionaryService dictionary = dictionaryFor(options);
        String dictionaryName = dictionary == localDictionary ? LOCAL_DICTIONARY : ALL_DICTIONARIES;
        Analysis analysis = analyze(file, deckId, options, dictionary != null, cancelled);
        int toLookUp = analysis.pendingCount();
        int lookedUp = 0;
        int skipped = analysis.skipped;
        List<Row> ready = new ArrayList<>();
        for (Row row : analysis.rows) {
            if (row.word != null) {
                ready.add(row);
                continue;
            }
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                throw new CancellationException("The import was canceled.");
            }
            DictionaryLookupResult result = dictionary.lookup(row.english);
            if (result.outcome() == LookupOutcome.INTERRUPTED) {
                throw new CancellationException("The import was canceled.");
            }
            progress.accept(new ImportProgress(++lookedUp, toLookUp));
            try {
                row.word = wordFromDictionary(row, result, deckId, dictionaryName);
                row.filled = true;
                ready.add(row);
            } catch (IllegalArgumentException e) {
                skipped++;
                analysis.messages.add("Line " + row.line + " skipped: " + e.getMessage());
            }
        }
        // The lookups can take minutes online, and the user can add a word meanwhile; a word
        // already in the deck would fail the whole transaction on the unique index.
        Set<String> inDeck = existingEnglishKeys(deckId);
        List<Row> stillNew = new ArrayList<>();
        for (Row row : ready) {
            if (inDeck.contains(key(row.english))) {
                skipped++;
                analysis.messages.add("Line " + row.line + " skipped: duplicate word " + row.english
                    + " (added to the deck during the import)");
            } else {
                stillNew.add(row);
            }
        }
        List<WordCard> words = stillNew.stream().map(row -> row.word).toList();
        int filled = (int) stillNew.stream().filter(row -> row.filled).count();
        try {
            int imported = wordRepository.insertAll(words);
            return new ImportResult(imported, skipped, analysis.messagesWithNotes(), filled,
                filled > 0 ? dictionaryName : "");
        } catch (SQLException e) {
            throw new IllegalStateException("The import transaction failed: " + ErrorMessages.rootMessage(e), e);
        }
    }

    /** The dictionary that fills in missing meanings: online only when allowed and offline mode is off. */
    private DictionaryService dictionaryFor(WordListOptions options) {
        if (options.onlineLookup() && onlineDictionary != null && !offline.getAsBoolean()) {
            return onlineDictionary.get();
        }
        return localDictionary;
    }

    private boolean canFill(WordListOptions options) {
        return localDictionary != null || (options.onlineLookup() && onlineDictionary != null && !offline.getAsBoolean());
    }

    /**
     * A word for a row without a meaning, from the dictionary's first entry with a Chinese meaning;
     * the row's own fields win over the dictionary's. Throws when there is none, with the reason.
     */
    private WordCard wordFromDictionary(Row row, DictionaryLookupResult result, long deckId, String dictionaryName) {
        if (!result.success()) {
            throw new IllegalArgumentException(result.unavailable()
                ? row.english + " could not be looked up (" + result.message() + ")"
                : row.english + " is not in " + dictionaryName + ", so it has no meaning to import");
        }
        DictionaryEntry entry = result.entries().stream()
            .filter(candidate -> candidate.chinese() != null && !candidate.chinese().isBlank())
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "the dictionaries have only an English definition of " + row.english + ", no Chinese meaning"));
        List<String> note = new ArrayList<>();
        if (!row.fields.get(WordColumn.NOTE).isBlank()) {
            note.add(row.fields.get(WordColumn.NOTE));
        }
        if (!entry.english().equalsIgnoreCase(row.english)) {
            note.add("Meaning of its base form " + entry.english() + ".");
        }
        if (entry.note() != null && !entry.note().isBlank()) {
            note.add(entry.note());
        }
        note.add("Meaning from " + entry.source() + ".");
        ValidatedWord validated = validationService.validate(
            row.english,
            entry.chinese(),
            orElse(row.fields.get(WordColumn.PHONETIC), entry.phonetic()),
            orElse(row.fields.get(WordColumn.POS), entry.partOfSpeech()),
            orElse(row.fields.get(WordColumn.EXAMPLE), entry.example()),
            String.join(" ", note),
            row.fields.get(WordColumn.TAGS));
        return newWord(deckId, validated);
    }

    private static String orElse(String value, String fallback) {
        return value == null || value.isBlank() ? (fallback == null ? "" : fallback) : value;
    }

    /**
     * Reads the whole file and decides, row by row, what an import would do. Nothing is written and
     * nothing is looked up. With {@code canFill}, a row without a meaning waits for a dictionary
     * lookup; otherwise it is invalid. Messages name the line each row starts on, which differs from
     * the row count when a quoted cell holds line breaks.
     */
    private Analysis analyze(WordListFile file, long deckId, WordListOptions options, boolean canFill,
                             BooleanSupplier cancelled) throws IOException {
        WordColumns columns;
        String layout;
        if (options.chosenColumns().isPresent()) {
            columns = options.columns();
            layout = " (chosen)";
            if (!columns.has(WordColumn.ENGLISH)) {
                throw new IllegalArgumentException("Choose the column that holds the English word.");
            }
        } else {
            columns = file.detectColumns();
            layout = file.describeLayout();
            if (file.layout() == WordListFile.Layout.HEADER_ROW) {
                CsvRecord header = file.headerRow().orElseThrow();
                requireColumn(columns, WordColumn.ENGLISH, header);
                if (!canFill) {
                    requireColumn(columns, WordColumn.CHINESE, header);
                }
            }
        }
        Analysis analysis = new Analysis(columns, layout);
        // The deck's words and the rows read so far, by lower-case English word.
        Set<String> seen = existingEnglishKeys(deckId);
        for (CsvRecord record = file.next(); record != null; record = file.next()) {
            if (record.isBlank()) {
                continue;
            }
            if (cancelled.getAsBoolean()) {
                throw new CancellationException("The import was canceled.");
            }
            analysis.totalRows++;
            if (analysis.rows.size() >= MAX_GRE_IMPORT_WORDS) {
                analysis.stoppedAtLimit = true;
                break;
            }
            Map<WordColumn, String> fields = file.fields(record, columns);
            Row row = new Row(record.lineNumber(), fields);
            analysis.sample(row);
            try {
                boolean needsMeaning = fields.get(WordColumn.CHINESE).isBlank() && canFill;
                String english;
                if (needsMeaning) {
                    english = validationService.validateEnglishOnly(fields.get(WordColumn.ENGLISH));
                } else {
                    ValidatedWord validated = validationService.validate(
                        fields.get(WordColumn.ENGLISH),
                        fields.get(WordColumn.CHINESE),
                        fields.get(WordColumn.PHONETIC),
                        fields.get(WordColumn.POS),
                        fields.get(WordColumn.EXAMPLE),
                        fields.get(WordColumn.NOTE),
                        fields.get(WordColumn.TAGS)
                    );
                    english = validated.english();
                    row.word = newWord(deckId, validated);
                }
                row.english = english;
                if (!seen.add(key(english))) {
                    row.word = null;
                    row.status = "Duplicate";
                    analysis.skipped++;
                    analysis.duplicates++;
                    analysis.messages.add("Line " + row.line + " skipped: duplicate word " + english);
                    continue;
                }
                row.status = needsMeaning ? NEEDS_MEANING : IMPORT;
                analysis.rows.add(row);
            } catch (IllegalArgumentException e) {
                row.status = e.getMessage();
                analysis.skipped++;
                analysis.messages.add("Line " + row.line + " skipped: " + e.getMessage());
            }
        }
        return analysis;
    }

    /**
     * The first rows as the preview shows them. A row without a meaning shows what the local
     * dictionary has for it, without asking anything online.
     */
    private List<ImportPreview.Row> previewRows(Analysis analysis, WordListOptions options) {
        boolean online = options.onlineLookup() && onlineDictionary != null && !offline.getAsBoolean();
        List<ImportPreview.Row> rows = new ArrayList<>();
        for (Row row : analysis.sample) {
            Map<WordColumn, String> fields = row.fields;
            String chinese = fields.get(WordColumn.CHINESE);
            String pos = fields.get(WordColumn.POS);
            String phonetic = fields.get(WordColumn.PHONETIC);
            String status = row.status;
            if (row.word != null) {
                chinese = row.word.getChinese();
            } else if (NEEDS_MEANING.equals(status)) {
                DictionaryLookupResult result = localDictionary == null
                    ? DictionaryLookupResult.notFound("")
                    : localDictionary.lookup(row.english);
                Optional<DictionaryEntry> entry = result.entries().stream()
                    .filter(candidate -> !candidate.chinese().isBlank())
                    .findFirst();
                if (entry.isPresent()) {
                    chinese = entry.get().chinese();
                    pos = orElse(pos, entry.get().partOfSpeech());
                    phonetic = orElse(phonetic, entry.get().phonetic());
                    status = "Meaning from " + entry.get().source();
                } else {
                    status = online ? "Not in the local dictionary: looked up online"
                        : "Not in the local dictionary: skipped";
                }
            }
            rows.add(new ImportPreview.Row(row.line, fields.get(WordColumn.ENGLISH), chinese, pos, phonetic,
                fields.get(WordColumn.EXAMPLE), fields.get(WordColumn.NOTE), fields.get(WordColumn.TAGS), status));
        }
        return rows;
    }

    private static WordCard newWord(long deckId, ValidatedWord validated) {
        WordCard word = WordCard.createNew(deckId, validated.english(), validated.chinese());
        word.setPhonetic(validated.phonetic());
        word.setPartOfSpeech(validated.partOfSpeech());
        word.setExampleSentence(validated.exampleSentence());
        word.setNote(validated.note());
        word.setTags(validated.tags());
        return word;
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

    private static String cannotWrite(String what, Path path, IOException e) {
        return "Cannot write the " + what + " " + path + ": " + reason(e);
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

    /** A row the import adds: with its word, or waiting for a meaning from the dictionary. */
    private static final class Row {
        final int line;
        final Map<WordColumn, String> fields;
        String english = "";
        WordCard word;
        /** True when the word's meaning came from the dictionary. */
        boolean filled;
        String status = "";

        Row(int line, Map<WordColumn, String> fields) {
            this.line = line;
            this.fields = fields;
        }
    }

    /** What an import of a file would do, row by row. */
    private static final class Analysis {
        final WordColumns columns;
        final String layout;
        final RowMessages messages = new RowMessages();
        /** The rows to import, in file order. */
        final List<Row> rows = new ArrayList<>();
        /** The first rows of the file, whatever happens to them, for the preview. */
        final List<Row> sample = new ArrayList<>();
        int totalRows;
        int skipped;
        int duplicates;
        boolean stoppedAtLimit;

        Analysis(WordColumns columns, String layout) {
            this.columns = columns;
            this.layout = layout;
        }

        void sample(Row row) {
            if (sample.size() < PREVIEW_ROWS) {
                sample.add(row);
            }
        }

        int readyCount() {
            return (int) rows.stream().filter(row -> row.word != null).count();
        }

        int pendingCount() {
            return rows.size() - readyCount();
        }

        List<String> messagesWithNotes() {
            List<String> all = new ArrayList<>(messages.toList());
            if (stoppedAtLimit) {
                all.add("GRE import stopped at " + MAX_GRE_IMPORT_WORDS + " imported words.");
            }
            return all;
        }
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
            if (intervalDays > 0 || consecutiveCorrect > 0) {
                // Reviewed before; a row without progress stays a new word.
                card.estimateStateFromLegacySchedule();
            }
            return card;
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("date format is invalid (" + e.getMessage() + ")", e);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("review parameters must be numeric (" + e.getMessage() + ")", e);
        }
    }
}
