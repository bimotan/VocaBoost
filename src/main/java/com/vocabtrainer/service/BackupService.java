package com.vocabtrainer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.vocabtrainer.domain.Achievement;
import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.ReviewKind;
import com.vocabtrainer.domain.ReviewLog;
import com.vocabtrainer.domain.ReviewMode;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.ValidatedWord;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.AchievementRepository;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.DatabaseSnapshots;
import com.vocabtrainer.repository.DeckRepository;
import com.vocabtrainer.repository.GoalRepository;
import com.vocabtrainer.repository.ReviewLogRepository;
import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.service.csv.CsvWriter;
import com.vocabtrainer.service.csv.WordColumn;
import com.vocabtrainer.service.csv.WordColumns;
import com.vocabtrainer.util.DateTimeUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.vocabtrainer.util.Messages.tr;

public class BackupService {
    private static final Logger LOGGER = Logger.getLogger(BackupService.class.getName());

    /**
     * What a restore does with backup words that are already in the target deck. A word the deck has
     * but never reviewed (new, no repetitions, no review logs) has no progress to keep: whatever the
     * policy, the backup's card replaces its schedule and suspension.
     */
    public enum ExistingWordPolicy {
        /** Keep the card's current review schedule. */
        KEEP_SCHEDULE,
        /** Replace the card's review schedule with the one saved in the backup. */
        OVERWRITE_SCHEDULE
    }

    private final DeckRepository deckRepository;
    private final WordRepository wordRepository;
    private final ReviewLogRepository reviewLogRepository;
    private final GoalRepository goalRepository;
    private final AchievementRepository achievementRepository;
    private final DatabaseManager databaseManager;
    private final WordValidationService validationService;
    private final Clock clock;
    private final CardStateBackfill cardStates;
    private final DatabaseSnapshots snapshots;
    private final ObjectMapper objectMapper = JsonMapper.builder()
        // Version 1 backups were written by hand and left tabs and other control characters unescaped.
        .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(SerializationFeature.INDENT_OUTPUT)
        .build();

    public BackupService(DeckRepository deckRepository, WordRepository wordRepository,
                         ReviewLogRepository reviewLogRepository, GoalRepository goalRepository,
                         AchievementRepository achievementRepository, DatabaseManager databaseManager,
                         WordValidationService validationService) {
        this(deckRepository, wordRepository, reviewLogRepository, goalRepository, achievementRepository,
            databaseManager, validationService, Clock.systemDefaultZone());
    }

    public BackupService(DeckRepository deckRepository, WordRepository wordRepository,
                         ReviewLogRepository reviewLogRepository, GoalRepository goalRepository,
                         AchievementRepository achievementRepository, DatabaseManager databaseManager,
                         WordValidationService validationService, Clock clock) {
        this(deckRepository, wordRepository, reviewLogRepository, goalRepository, achievementRepository,
            databaseManager, validationService, clock,
            new CardStateBackfill(wordRepository, reviewLogRepository, new ReviewScheduler()));
    }

    /** @param cardStates derives the FSRS state of words restored from a backup written before FSRS */
    public BackupService(DeckRepository deckRepository, WordRepository wordRepository,
                         ReviewLogRepository reviewLogRepository, GoalRepository goalRepository,
                         AchievementRepository achievementRepository, DatabaseManager databaseManager,
                         WordValidationService validationService, Clock clock, CardStateBackfill cardStates) {
        this(deckRepository, wordRepository, reviewLogRepository, goalRepository, achievementRepository,
            databaseManager, validationService, clock, cardStates, null);
    }

    /**
     * @param cardStates derives the FSRS state of words restored from a backup written before FSRS
     * @param snapshots  writes a snapshot of the database before each restore; null for none
     */
    public BackupService(DeckRepository deckRepository, WordRepository wordRepository,
                         ReviewLogRepository reviewLogRepository, GoalRepository goalRepository,
                         AchievementRepository achievementRepository, DatabaseManager databaseManager,
                         WordValidationService validationService, Clock clock, CardStateBackfill cardStates,
                         DatabaseSnapshots snapshots) {
        this.deckRepository = deckRepository;
        this.wordRepository = wordRepository;
        this.reviewLogRepository = reviewLogRepository;
        this.goalRepository = goalRepository;
        this.achievementRepository = achievementRepository;
        this.databaseManager = databaseManager;
        this.validationService = validationService;
        this.clock = clock;
        this.cardStates = cardStates;
        this.snapshots = snapshots;
    }

    /**
     * Writes the deck's words, suspended ones included, as CSV for spreadsheets ({@link CsvWriter}:
     * UTF-8 with BOM, formula cells neutralized). The header row uses the names "Import word list"
     * maps columns by, so the file imports back with every field in place.
     */
    public Path exportWordsCsv(long deckId, Path outputPath) {
        try {
            List<WordCard> words = wordRepository.findAllIncludingSuspended(deckId);
            ensureParent(outputPath);
            try (CsvWriter writer = CsvWriter.create(outputPath)) {
                writer.writeRow(WordColumns.EXPORT_ORDER.stream().map(WordColumn::headerName).toList());
                for (WordCard word : words) {
                    writer.writeRow(WordColumns.EXPORT_ORDER.stream().map(column -> wordField(word, column)).toList());
                }
            }
            return outputPath;
        } catch (IOException | SQLException e) {
            throw new IllegalStateException(tr("backup.error.exportWords"), e);
        }
    }

    private static String wordField(WordCard word, WordColumn column) {
        return switch (column) {
            case ENGLISH -> word.getEnglish();
            case CHINESE -> word.getChinese();
            case PHONETIC -> word.getPhonetic();
            case POS -> word.getPartOfSpeech();
            case EXAMPLE -> word.getExampleSentence();
            case NOTE -> word.getNote();
            case TAGS -> word.getTags();
        };
    }

    public Path exportReviewLogsCsv(long deckId, Path outputPath) {
        try {
            ensureParent(outputPath);
            // One transaction, so every log's word is in the word list read alongside it.
            List<List<String>> rows = databaseManager.inTransaction(() -> reviewLogCsvRows(deckId));
            try (CsvWriter writer = CsvWriter.create(outputPath)) {
                for (List<String> row : rows) {
                    writer.writeRow(row);
                }
            }
            return outputPath;
        } catch (IOException | SQLException e) {
            throw new IllegalStateException(tr("backup.error.exportLogs"), e);
        }
    }

    private List<List<String>> reviewLogCsvRows(long deckId) throws SQLException {
        Map<Long, String> englishById = new HashMap<>();
        for (WordCard word : wordRepository.findAllIncludingSuspended(deckId)) {
            englishById.put(word.getId(), word.getEnglish());
        }
        List<List<String>> rows = new ArrayList<>();
        // "rating" is the one the user chose, "effective_rating" what the schedule counted it as.
        rows.add(List.of("english", "reviewed_at", "user_answer", "correct_answer", "similarity", "rating",
            "elapsed_millis", "effective_rating", "overridden", "kind", "direction"));
        for (ReviewLog log : reviewLogRepository.findByDeck(deckId)) {
            rows.add(Arrays.asList(
                englishById.get(log.getWordId()),
                DateTimeUtil.toDatabase(log.getReviewedAt()),
                log.getUserAnswer(),
                log.getCorrectAnswer(),
                String.valueOf(log.getSimilarity()),
                log.getRating().name(),
                String.valueOf(log.getElapsedMillis()),
                log.getEffectiveRating().name(),
                String.valueOf(log.isOverridden()),
                log.getKind().name(),
                log.getDirection() == null ? "" : log.getDirection().name()
            ));
        }
        return rows;
    }

    /** Writes the deck's words with their review schedule, review logs, goal history and achievements. */
    public Path exportJsonBackup(long deckId, Path outputPath) {
        try {
            // One transaction, so words and logs come from the same moment.
            BackupFile backup = databaseManager.inTransaction(() -> buildBackup(deckId));
            ensureParent(outputPath);
            // Written as text so emoji stay readable characters instead of escaped surrogate pairs.
            Files.writeString(outputPath, objectMapper.writeValueAsString(backup), StandardCharsets.UTF_8);
            return outputPath;
        } catch (IOException | SQLException e) {
            throw new IllegalStateException(tr("backup.error.export"), e);
        }
    }

    public BackupRestoreResult importJsonBackup(Path inputPath, long deckId) {
        return importJsonBackup(inputPath, deckId, ExistingWordPolicy.KEEP_SCHEDULE);
    }

    /**
     * Restores a version 1 or 2 backup into the deck in one transaction: either everything valid in
     * the file is restored or, if the database fails, nothing is. Rows the file gets wrong are skipped
     * and listed in the result. Restoring the same file again adds nothing. A snapshot of the database
     * is written first (one that cannot be written is logged and does not stop the restore).
     */
    public BackupRestoreResult importJsonBackup(Path inputPath, long deckId, ExistingWordPolicy policy) {
        JsonNode root = readBackup(inputPath);
        int version = formatVersion(root);
        return restoreInTransaction(inputPath, () -> {
            Deck deck = deckRepository.findById(deckId)
                .orElseThrow(() -> new IllegalArgumentException(tr("deck.error.notFound")));
            return restore(root, version, deck, false, policy);
        });
    }

    /**
     * Restores a backup into a new deck, named as the deck the backup was made from (or, for a backup
     * that does not say, after the file), with " (2)", " (3)", ... appended when an active deck has
     * that name; otherwise as {@link #importJsonBackup(Path, long, ExistingWordPolicy)}. Every word is
     * new there, so the whole backup is restored. The deck is created in the restore's transaction.
     */
    public BackupRestoreResult importJsonBackupIntoNewDeck(Path inputPath) {
        JsonNode root = readBackup(inputPath);
        int version = formatVersion(root);
        return restoreInTransaction(inputPath, () -> {
            Deck deck = deckRepository.create(freeDeckName(backupDeckName(root, inputPath)));
            return restore(root, version, deck, true, ExistingWordPolicy.KEEP_SCHEDULE);
        });
    }

    /** Writes a snapshot, then runs {@code restore} in one transaction and logs the rows it skipped. */
    private BackupRestoreResult restoreInTransaction(Path inputPath, SqlRestore restore) {
        if (snapshots != null) {
            snapshots.takeQuietly("before-restore");
        }
        RestoreTally tally;
        try {
            tally = databaseManager.inTransaction(restore::run);
        } catch (SQLException e) {
            throw new IllegalStateException(tr("backup.error.restore"), e);
        }
        if (!tally.invalidRows.isEmpty()) {
            LOGGER.log(Level.WARNING, "Skipped " + tally.invalidRows.size() + " invalid row(s) while restoring "
                + inputPath + System.lineSeparator() + String.join(System.lineSeparator(), tally.invalidRows),
                tally.firstFailure);
        }
        return tally.toResult();
    }

    private BackupFile buildBackup(long deckId) throws SQLException {
        String deckName = deckRepository.findById(deckId).map(Deck::getName).orElse(null);
        List<BackupFile.WordEntry> words = new ArrayList<>();
        Map<Long, String> englishById = new HashMap<>();
        for (WordCard word : wordRepository.findAllIncludingSuspended(deckId)) {
            englishById.put(word.getId(), word.getEnglish());
            words.add(new BackupFile.WordEntry(
                word.getEnglish(),
                word.getChinese(),
                word.getPhonetic(),
                word.getPartOfSpeech(),
                word.getExampleSentence(),
                word.getNote(),
                word.getTags(),
                DateTimeUtil.toDatabase(word.getAddedAt()),
                DateTimeUtil.toDatabase(word.getLastReviewedAt()),
                DateTimeUtil.toDatabase(word.getNextReviewAt()),
                word.getEasinessFactor(),
                word.getIntervalDays(),
                word.getRepetitions(),
                word.getConsecutiveCorrect(),
                word.getLapses(),
                word.isSuspended(),
                word.getState().name(),
                word.getStability(),
                word.getDifficulty(),
                word.getLearningStep()
            ));
        }
        List<BackupFile.ReviewLogEntry> logs = new ArrayList<>();
        for (ReviewLog log : reviewLogRepository.findByDeck(deckId)) {
            logs.add(new BackupFile.ReviewLogEntry(
                englishById.get(log.getWordId()),
                DateTimeUtil.toDatabase(log.getReviewedAt()),
                log.getUserAnswer(),
                log.getCorrectAnswer(),
                log.getSimilarity(),
                log.getRating().name(),
                log.getElapsedMillis(),
                log.getKind().name(),
                log.getDirection() == null ? null : log.getDirection().name(),
                log.getRecordedEffectiveRating() == null ? null : log.getRecordedEffectiveRating().name(),
                log.isOverridden()
            ));
        }
        List<BackupFile.DailyGoalEntry> goals = new ArrayList<>();
        for (GoalRepository.GoalRow row : goalRepository.findAll(deckId)) {
            goals.add(new BackupFile.DailyGoalEntry(
                row.date().toString(),
                row.reviewGoal(),
                row.newWordGoal(),
                row.sessionGoal(),
                row.reviewedCount(),
                row.correctCount(),
                row.newWordsCount(),
                row.xpEarned(),
                row.completed()
            ));
        }
        List<BackupFile.AchievementEntry> achievements = new ArrayList<>();
        // What the deck shows: its own badges and the streak badges, which belong to no deck.
        for (Achievement achievement : achievementRepository.findShown(deckId, AchievementService.STREAK_CODES)) {
            achievements.add(new BackupFile.AchievementEntry(
                achievement.code(),
                achievement.name(),
                achievement.description(),
                DateTimeUtil.toDatabase(achievement.unlockedAt()),
                achievement.xpReward()
            ));
        }
        return new BackupFile(BackupFile.FORMAT, BackupFile.VERSION, DateTimeUtil.toDatabase(LocalDateTime.now(clock)),
            new BackupFile.DeckEntry(deckName), words, logs, goals, achievements);
    }

    private JsonNode readBackup(Path inputPath) {
        try (InputStream input = Files.newInputStream(inputPath)) {
            return objectMapper.readTree(input);
        } catch (IOException e) {
            throw new IllegalStateException(tr("backup.error.read"), e);
        }
    }

    private int formatVersion(JsonNode root) {
        if (root == null || !root.isObject() || !root.path("words").isArray()
            || (root.hasNonNull("format") && !BackupFile.FORMAT.equals(root.get("format").asText()))) {
            throw new IllegalArgumentException(tr("backup.error.notBackup"));
        }
        JsonNode version = root.get("version");
        // Version 1 files may lack the field; their writer also put numbers in strings.
        int number = version == null || version.isNull() ? 1 : version.asInt(-1);
        if (number < 1 || number > BackupFile.VERSION) {
            throw new IllegalArgumentException(tr("backup.error.version", version.asText(), BackupFile.VERSION));
        }
        return number;
    }

    /** The name of the deck the backup was made from, else the file's name without .json. */
    private String backupDeckName(JsonNode root, Path inputPath) {
        String name = root.path("deck").path("name").asText("").trim().replaceAll("\\s+", " ");
        if (name.isEmpty()) {
            name = inputPath.getFileName().toString().replaceFirst("(?i)\\.json$", "").trim().replaceAll("\\s+", " ");
        }
        return name.isEmpty() ? tr("backup.newDeck.name") : name;
    }

    /**
     * {@code name}, or {@code name} with " (2)", " (3)", ... appended: the first no active deck has,
     * ignoring case, shortened to the longest deck name allowed.
     */
    private String freeDeckName(String name) throws SQLException {
        String candidate = shorten(name, "");
        for (int number = 2; deckRepository.findByName(candidate).isPresent(); number++) {
            candidate = shorten(name, " (" + number + ")");
        }
        return candidate;
    }

    private static String shorten(String name, String suffix) {
        int room = DeckService.MAX_DECK_NAME_LENGTH - suffix.length();
        return (name.length() > room ? name.substring(0, room).trim() : name) + suffix;
    }

    /**
     * Restores the backup into {@code deck}. The review logs of a word are restored only when the
     * word's schedule is the backup's after the restore (the word was added, its schedule replaced,
     * or it already had it): the logs of a word that keeps its own, different progress are counted
     * but not added, so they cannot inflate that card's history.
     */
    private RestoreTally restore(JsonNode root, int version, Deck deck, boolean deckCreated, ExistingWordPolicy policy)
        throws SQLException {
        long deckId = deck.getId();
        RestoreTally tally = new RestoreTally(version, deck, deckCreated);
        Map<String, WordCard> deckWords = new HashMap<>();
        for (WordCard word : wordRepository.findAllIncludingSuspended(deckId)) {
            deckWords.put(wordKey(word.getEnglish()), word);
        }
        Set<Long> reviewedWords = reviewLogRepository.wordsWithLogs(deckId);

        Set<String> restoredWords = new HashSet<>();
        Set<Long> takesBackupLogs = new HashSet<>();
        List<WordCard> withoutCardState = new ArrayList<>();
        restoreRows(root, "words", tr("backup.row.word"), tally,
            row -> restoreWord(row, deckId, policy, deckWords, reviewedWords, restoredWords, takesBackupLogs,
                withoutCardState, tally));

        List<ReviewLog> logs = new ArrayList<>();
        restoreRows(root, "reviewLogs", tr("backup.row.reviewLog"), tally, row -> {
            ReviewLog log = reviewLog(row, deckWords);
            if (takesBackupLogs.contains(log.getWordId())) {
                logs.add(log);
            } else {
                tally.keptWordLogsSkipped++;
            }
        });
        // A log with the same word, time and rating is the same review, already in the deck or earlier in the file.
        int logsInserted = reviewLogRepository.insertAllIfAbsent(logs);
        tally.logsInserted += logsInserted;
        tally.duplicateLogsSkipped += logs.size() - logsInserted;
        // Words from a backup without FSRS state get it from their review history, now complete.
        for (WordCard word : withoutCardState) {
            cardStates.deriveAndSave(word);
        }
        restoreRows(root, "dailyGoals", tr("backup.row.dailyGoal"), tally, row -> restoreDailyGoal(row, deckId, tally));
        restoreRows(root, "achievements", tr("backup.row.achievement"), tally,
            row -> restoreAchievement(row, deckId, tally));
        return tally;
    }

    private void restoreRows(JsonNode root, String field, String rowName, RestoreTally tally, RowRestorer restorer)
        throws SQLException {
        JsonNode rows = root.path(field);
        if (rows.isMissingNode() || rows.isNull()) {
            return;
        }
        if (!rows.isArray()) {
            tally.invalid(tr("backup.invalid.notList", field), null);
            return;
        }
        int index = 0;
        for (JsonNode row : rows) {
            index++;
            if (!row.isObject()) {
                tally.invalid(tr("backup.invalid.row", rowName, String.valueOf(index), tr("backup.invalid.notObject")), null);
                continue;
            }
            try {
                restorer.restore(row);
            } catch (JsonProcessingException e) {
                tally.invalid(tr("backup.invalid.row", rowName, index + rowLabel(row), e.getOriginalMessage()), e);
            } catch (IllegalArgumentException e) {
                tally.invalid(tr("backup.invalid.row", rowName, index + rowLabel(row), e.getMessage()), e);
            }
        }
    }

    /**
     * Adds the backup's word, or decides what happens to the deck's word of that spelling; see
     * {@link ExistingWordPolicy}. The ids of the words whose schedule is the backup's afterwards go
     * into {@code takesBackupLogs}.
     */
    private void restoreWord(JsonNode row, long deckId, ExistingWordPolicy policy, Map<String, WordCard> deckWords,
                             Set<Long> reviewedWords, Set<String> restoredWords, Set<Long> takesBackupLogs,
                             List<WordCard> withoutCardState, RestoreTally tally)
        throws SQLException, JsonProcessingException {
        BackupFile.WordEntry entry = objectMapper.treeToValue(row, BackupFile.WordEntry.class);
        ValidatedWord validated = validationService.validate(entry.english(), entry.chinese(), entry.phonetic(),
            entry.partOfSpeech(), entry.exampleSentence(), entry.note(), entry.tags());
        LocalDateTime addedAt = dateTime(entry.addedAt(), "addedAt");
        Schedule schedule = scheduleOf(entry);
        String key = wordKey(validated.english());
        if (!restoredWords.add(key)) {
            throw new IllegalArgumentException(tr("backup.invalid.repeatedWord"));
        }

        WordCard existing = deckWords.get(key);
        if (existing == null) {
            LocalDateTime now = LocalDateTime.now(clock);
            WordCard word = new WordCard();
            word.setDeckId(deckId);
            word.setEnglish(validated.english());
            // Text is restored exactly as saved; validation only decides whether the row is usable.
            word.setChinese(entry.chinese());
            word.setPhonetic(entry.phonetic());
            word.setPartOfSpeech(entry.partOfSpeech());
            word.setExampleSentence(entry.exampleSentence());
            word.setNote(entry.note());
            word.setTags(entry.tags());
            word.setAddedAt(addedAt == null ? now : addedAt);
            (schedule == null ? Schedule.newCard(now) : schedule).applyTo(word);
            word.setSuspended(Boolean.TRUE.equals(entry.archived()));
            wordRepository.insert(word);
            deckWords.put(key, word);
            takesBackupLogs.add(word.getId());
            if (schedule != null && schedule.state() == null) {
                withoutCardState.add(word);
            }
            tally.wordsInserted++;
            return;
        }
        // Never reviewed: nothing of its own to keep, so the backup's card replaces it, unless that
        // card was never reviewed or suspended either.
        boolean unreviewed = existing.getState() == CardState.NEW && existing.getRepetitions() == 0
            && !reviewedWords.contains(existing.getId());
        boolean backupSuspended = Boolean.TRUE.equals(entry.archived());
        boolean replace = schedule != null && (policy == ExistingWordPolicy.OVERWRITE_SCHEDULE
            || (unreviewed && (!schedule.isNewCard() || backupSuspended)));
        boolean suspend = unreviewed && replace && backupSuspended != existing.isSuspended();
        if (replace && (!schedule.sameAs(existing) || suspend)) {
            schedule.applyTo(existing);
            if (unreviewed) {
                existing.setSuspended(backupSuspended);
            }
            wordRepository.update(existing);
            if (schedule.state() == null) {
                withoutCardState.add(existing);
            }
            takesBackupLogs.add(existing.getId());
            tally.wordsUpdated++;
            return;
        }
        if (schedule != null && schedule.sameAs(existing)) {
            // Already the backup's schedule, as when restoring into the deck the backup came from.
            takesBackupLogs.add(existing.getId());
        }
        tally.wordsSkipped++;
    }

    /** The saved review schedule, or null for a version 1 entry, which has none. */
    private Schedule scheduleOf(BackupFile.WordEntry entry) {
        LocalDateTime nextReviewAt = dateTime(entry.nextReviewAt(), "nextReviewAt");
        if (nextReviewAt == null) {
            return null;
        }
        double easiness = entry.easinessFactor() == null ? WordCard.DEFAULT_EASINESS : entry.easinessFactor();
        if (!Double.isFinite(easiness) || easiness <= 0) {
            throw new IllegalArgumentException(tr("backup.invalid.value", "easinessFactor", String.valueOf(easiness)));
        }
        CardState state = cardState(entry.cardState());
        return new Schedule(
            dateTime(entry.lastReviewedAt(), "lastReviewedAt"),
            nextReviewAt,
            easiness,
            count(entry.intervalDays(), 0, "intervalDays"),
            count(entry.repetitions(), 0, "repetitions"),
            count(entry.consecutiveCorrect(), 0, "consecutiveCorrect"),
            count(entry.lapses(), 0, "lapses"),
            state,
            state == null ? 0 : memory(entry.stability(), "stability"),
            state == null ? 0 : memory(entry.difficulty(), "difficulty"),
            state == null ? 0 : count(entry.learningStep(), 0, "learningStep")
        );
    }

    /** The saved FSRS state, or null for a backup written before FSRS. */
    private static CardState cardState(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return CardState.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(tr("backup.invalid.unknown", "cardState", value), e);
        }
    }

    private static double memory(Double value, String field) {
        if (value == null) {
            return 0;
        }
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(tr("backup.invalid.value", field, String.valueOf(value)));
        }
        return value;
    }

    /** The backup row as a log of the deck's word, or IllegalArgumentException if the row is unusable. */
    private ReviewLog reviewLog(JsonNode row, Map<String, WordCard> deckWords) throws JsonProcessingException {
        BackupFile.ReviewLogEntry entry = objectMapper.treeToValue(row, BackupFile.ReviewLogEntry.class);
        String english = validationService.normalizeEnglish(entry.english());
        if (english.isEmpty()) {
            throw new IllegalArgumentException(tr("backup.invalid.missing", "english"));
        }
        WordCard word = deckWords.get(wordKey(english));
        if (word == null) {
            throw new IllegalArgumentException(tr("backup.invalid.noWord", english));
        }
        LocalDateTime reviewedAt = dateTime(entry.reviewedAt(), "reviewedAt");
        if (reviewedAt == null) {
            throw new IllegalArgumentException(tr("backup.invalid.missing", "reviewedAt"));
        }
        ReviewRating rating = rating(entry.rating());
        if (entry.similarity() == null || !Double.isFinite(entry.similarity())) {
            throw new IllegalArgumentException(tr("backup.invalid.missingOrInvalid", "similarity"));
        }
        return new ReviewLog(
            0,
            word.getId(),
            reviewedAt,
            entry.userAnswer(),
            entry.correctAnswer() == null ? "" : entry.correctAnswer(),
            entry.similarity(),
            rating,
            entry.elapsedMillis() == null ? 0L : entry.elapsedMillis(),
            reviewKind(entry.kind()),
            direction(entry.direction()),
            entry.effectiveRating() == null || entry.effectiveRating().isBlank() ? null : rating(entry.effectiveRating()),
            Boolean.TRUE.equals(entry.overridden())
        );
    }

    /** The logged kind; a backup written before kinds were logged has none, which is a review. */
    private static ReviewKind reviewKind(String value) {
        if (value == null || value.isBlank()) {
            return ReviewKind.REVIEW;
        }
        try {
            return ReviewKind.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(tr("backup.invalid.unknown", "kind", value), e);
        }
    }

    /** The logged question direction, or null when the backup does not say. */
    private static ReviewMode direction(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String name = value.trim().toUpperCase(Locale.ROOT);
        for (ReviewMode direction : ReviewMode.values()) {
            if (direction.isDirection() && direction.name().equals(name)) {
                return direction;
            }
        }
        throw new IllegalArgumentException(tr("backup.invalid.unknown", "direction", value));
    }

    private void restoreDailyGoal(JsonNode row, long deckId, RestoreTally tally)
        throws SQLException, JsonProcessingException {
        BackupFile.DailyGoalEntry entry = objectMapper.treeToValue(row, BackupFile.DailyGoalEntry.class);
        if (entry.date() == null || entry.date().isBlank()) {
            throw new IllegalArgumentException(tr("backup.invalid.missing", "date"));
        }
        LocalDate date;
        try {
            date = LocalDate.parse(entry.date().trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(tr("backup.invalid.quoted", "date", entry.date()), e);
        }
        GoalRepository.GoalRow goal = new GoalRepository.GoalRow(
            deckId,
            date,
            count(entry.reviewGoal(), GoalService.DEFAULT_REVIEW_GOAL, "reviewGoal"),
            count(entry.newWordGoal(), GoalService.DEFAULT_NEW_WORD_GOAL, "newWordGoal"),
            count(entry.sessionGoal(), GoalService.DEFAULT_SESSION_GOAL, "sessionGoal"),
            count(entry.reviewedCount(), 0, "reviewedCount"),
            count(entry.correctCount(), 0, "correctCount"),
            count(entry.newWordsCount(), 0, "newWordsCount"),
            count(entry.xpEarned(), 0, "xpEarned"),
            Boolean.TRUE.equals(entry.completed())
        );
        if (goalRepository.restoreRow(goal)) {
            tally.dailyGoalsRestored++;
        }
    }

    private void restoreAchievement(JsonNode row, long deckId, RestoreTally tally)
        throws SQLException, JsonProcessingException {
        BackupFile.AchievementEntry entry = objectMapper.treeToValue(row, BackupFile.AchievementEntry.class);
        String code = entry.code() == null ? "" : entry.code().trim();
        if (code.isEmpty()) {
            throw new IllegalArgumentException(tr("backup.invalid.missing", "code"));
        }
        LocalDateTime unlockedAt = dateTime(entry.unlockedAt(), "unlockedAt");
        if (unlockedAt == null) {
            throw new IllegalArgumentException(tr("backup.invalid.missing", "unlockedAt"));
        }
        Achievement achievement = new Achievement(
            code,
            entry.name() == null ? code : entry.name(),
            entry.description() == null ? "" : entry.description(),
            unlockedAt,
            count(entry.xpReward(), 0, "xpReward")
        );
        boolean restored;
        if (AchievementService.STREAK_CODES.contains(code)) {
            // The streak counts every deck, so its badges belong to no deck; one this database has
            // in any deck already is not added again (nor unlocked again later, with its XP).
            restored = !achievementRepository.existsInAnyDeck(code)
                && achievementRepository.insertIfAbsent(AchievementRepository.NO_DECK, achievement);
        } else {
            restored = achievementRepository.insertIfAbsent(deckId, achievement);
        }
        if (restored) {
            tally.achievementsRestored++;
        }
    }

    private static String wordKey(String english) {
        // English words are ASCII (see WordValidationService), matching the NOCASE unique index.
        return english.trim().toLowerCase(Locale.ROOT);
    }

    private static String rowLabel(JsonNode row) {
        for (String field : List.of("english", "wordEnglish", "code", "date")) {
            JsonNode value = row.get(field);
            if (value != null && value.isValueNode() && !value.asText().isBlank()) {
                return " (" + value.asText().trim() + ")";
            }
        }
        return "";
    }

    private static LocalDateTime dateTime(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return DateTimeUtil.fromDatabase(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(tr("backup.invalid.quoted", field, value), e);
        }
    }

    private static int count(Integer value, int fallback, String field) {
        if (value == null) {
            return fallback;
        }
        if (value < 0) {
            throw new IllegalArgumentException(tr("backup.invalid.negative", field, String.valueOf(value)));
        }
        return value;
    }

    private static ReviewRating rating(String value) {
        if (value != null) {
            for (ReviewRating rating : ReviewRating.values()) {
                if (rating.name().equalsIgnoreCase(value.trim())) {
                    return rating;
                }
            }
        }
        throw new IllegalArgumentException(tr("backup.invalid.unknown", "rating", value));
    }

    private void ensureParent(Path outputPath) throws IOException {
        Path parent = outputPath.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    @FunctionalInterface
    private interface RowRestorer {
        void restore(JsonNode row) throws SQLException, JsonProcessingException;
    }

    @FunctionalInterface
    private interface SqlRestore {
        RestoreTally run() throws SQLException;
    }

    /**
     * The review-schedule columns of a card. {@code state} is null for a backup written before FSRS;
     * {@link #applyTo} then estimates the FSRS state from the SM-2 fields until it is derived.
     */
    private record Schedule(
        LocalDateTime lastReviewedAt,
        LocalDateTime nextReviewAt,
        double easinessFactor,
        int intervalDays,
        int repetitions,
        int consecutiveCorrect,
        int lapses,
        CardState state,
        double stability,
        double difficulty,
        int learningStep
    ) {
        static Schedule newCard(LocalDateTime now) {
            return new Schedule(null, now, WordCard.DEFAULT_EASINESS, 0, 0, 0, 0, CardState.NEW, 0, 0, 0);
        }

        /** Whether the saved card was never reviewed, so it has no progress to bring. */
        boolean isNewCard() {
            return (state == null || state == CardState.NEW) && repetitions == 0 && lastReviewedAt == null;
        }

        static Schedule of(WordCard word) {
            return new Schedule(word.getLastReviewedAt(), word.getNextReviewAt(), word.getEasinessFactor(),
                word.getIntervalDays(), word.getRepetitions(), word.getConsecutiveCorrect(), word.getLapses(),
                word.getState(), word.getStability(), word.getDifficulty(), word.getLearningStep());
        }

        /** Whether the card already has this schedule, as far as the backup records it. */
        boolean sameAs(WordCard word) {
            Schedule current = of(word);
            return state == null
                ? equals(new Schedule(current.lastReviewedAt, current.nextReviewAt, current.easinessFactor,
                    current.intervalDays, current.repetitions, current.consecutiveCorrect, current.lapses, null, 0, 0, 0))
                : equals(current);
        }

        void applyTo(WordCard word) {
            word.setLastReviewedAt(lastReviewedAt);
            word.setNextReviewAt(nextReviewAt);
            word.setEasinessFactor(easinessFactor);
            word.setIntervalDays(intervalDays);
            word.setRepetitions(repetitions);
            word.setConsecutiveCorrect(consecutiveCorrect);
            word.setLapses(lapses);
            if (state == null) {
                word.estimateStateFromLegacySchedule();
            } else {
                word.setState(state);
                word.setStability(stability);
                word.setDifficulty(difficulty);
                word.setLearningStep(learningStep);
            }
        }
    }

    private static final class RestoreTally {
        private final int formatVersion;
        private final Deck deck;
        private final boolean deckCreated;
        private int wordsInserted;
        private int wordsUpdated;
        private int wordsSkipped;
        private int logsInserted;
        private int duplicateLogsSkipped;
        private int keptWordLogsSkipped;
        private int dailyGoalsRestored;
        private int achievementsRestored;
        private final List<String> invalidRows = new ArrayList<>();
        private Exception firstFailure;

        private RestoreTally(int formatVersion, Deck deck, boolean deckCreated) {
            this.formatVersion = formatVersion;
            this.deck = deck;
            this.deckCreated = deckCreated;
        }

        private void invalid(String row, Exception failure) {
            invalidRows.add(row);
            if (firstFailure == null) {
                firstFailure = failure;
            }
        }

        private BackupRestoreResult toResult() {
            return new BackupRestoreResult(deck, deckCreated, formatVersion, wordsInserted, wordsUpdated, wordsSkipped,
                logsInserted, duplicateLogsSkipped, keptWordLogsSkipped, dailyGoalsRestored, achievementsRestored,
                invalidRows);
        }
    }
}
