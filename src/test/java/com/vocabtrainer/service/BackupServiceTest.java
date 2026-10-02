package com.vocabtrainer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vocabtrainer.domain.Achievement;
import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.ReviewLog;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.AchievementRepository;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.DeckRepository;
import com.vocabtrainer.repository.GoalRepository;
import com.vocabtrainer.repository.ReviewLogRepository;
import com.vocabtrainer.repository.TestDatabases;
import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.util.ErrorMessages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-28T09:00:00Z"), ZoneId.of("UTC"));
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);
    private static final LocalDate TODAY = NOW.toLocalDate();
    private static final String TRICKY_NOTE =
        "see [GRE] list ] {usu. pl.} \"quoted\" back\\slash\ttab\nnew line \\u0041 😀 end";

    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    @Test
    void exportsCsvFiles() throws Exception {
        Db db = new Db(tempDir.resolve("csv.db"));
        Deck deck = db.decks.ensureDefaultDeck();
        WordCard word = WordCard.createNew(deck.getId(), "lucid", "清晰的");
        word.setTags("backup");
        db.words.save(word);
        db.logs.insert(new ReviewLog(0, word.getId(), NOW, "清晰的", "清晰的", 1.0, ReviewRating.EASY, 900));

        Path wordsCsv = db.backup.exportWordsCsv(deck.getId(), tempDir.resolve("words.csv"));
        Path logsCsv = db.backup.exportReviewLogsCsv(deck.getId(), tempDir.resolve("logs.csv"));

        assertTrue(Files.readString(wordsCsv, StandardCharsets.UTF_8).contains("lucid"));
        assertTrue(Files.readString(logsCsv, StandardCharsets.UTF_8).contains("EASY"));
    }

    @Test
    void roundTripRestoresEveryFieldScheduleLogGoalAndAchievement() throws Exception {
        Db source = new Db(tempDir.resolve("source.db"));
        Deck deck = source.decks.create("GRE [core] {1}");
        seedTrickyDeck(source, deck.getId());

        Path json = source.backup.exportJsonBackup(deck.getId(), tempDir.resolve("backup.json"));

        // The file is standard JSON that any strict parser reads, not just our own reader.
        JsonNode parsed = new ObjectMapper().readTree(Files.readString(json, StandardCharsets.UTF_8));
        assertEquals("vocaboost-backup", parsed.get("format").asText());
        assertEquals(2, parsed.get("version").asInt());
        assertEquals("GRE [core] {1}", parsed.get("deck").get("name").asText());
        assertEquals(4, parsed.get("words").size());

        Db target = new Db(tempDir.resolve("target.db"));
        Deck restoredDeck = target.decks.ensureDefaultDeck();
        BackupRestoreResult result = target.backup.importJsonBackup(json, restoredDeck.getId());

        assertEquals(2, result.formatVersion());
        assertEquals(4, result.wordsInserted());
        assertEquals(0, result.wordsUpdated());
        assertEquals(0, result.wordsSkipped());
        assertEquals(3, result.logsInserted());
        assertEquals(0, result.duplicateLogsSkipped());
        assertEquals(2, result.dailyGoalsRestored());
        assertEquals(1, result.achievementsRestored());
        assertTrue(result.invalidRows().isEmpty(), result.invalidRows().toString());

        Map<String, WordCard> restored = byEnglish(target.words.findAllIncludingArchived(restoredDeck.getId()));
        List<WordCard> originals = source.words.findAllIncludingArchived(deck.getId());
        assertEquals(originals.size(), restored.size());
        for (WordCard original : originals) {
            assertSameWord(original, restored.get(original.getEnglish()));
        }
        assertEquals(logSnapshot(source, deck.getId()), logSnapshot(target, restoredDeck.getId()));
        assertEquals(goalSnapshot(source, deck.getId()), goalSnapshot(target, restoredDeck.getId()));
        assertEquals(source.achievements.findAll(deck.getId()), target.achievements.findAll(restoredDeck.getId()));
    }

    @Test
    void restoringTheSameBackupAgainAddsNothing() throws Exception {
        Db db = new Db(tempDir.resolve("twice.db"));
        Deck deck = db.decks.ensureDefaultDeck();
        seedTrickyDeck(db, deck.getId());
        Path json = db.backup.exportJsonBackup(deck.getId(), tempDir.resolve("backup.json"));
        Deck other = db.decks.create("Restored");

        BackupRestoreResult first = db.backup.importJsonBackup(json, other.getId());
        int logsAfterFirst = db.logs.findByDeck(other.getId()).size();
        BackupRestoreResult second = db.backup.importJsonBackup(json, other.getId());

        assertEquals(4, first.wordsInserted());
        assertEquals(3, first.logsInserted());
        assertEquals(0, second.wordsInserted());
        assertEquals(4, second.wordsSkipped());
        assertEquals(0, second.logsInserted());
        assertEquals(3, second.duplicateLogsSkipped());
        assertEquals(0, second.dailyGoalsRestored());
        assertEquals(0, second.achievementsRestored());
        assertEquals(logsAfterFirst, db.logs.findByDeck(other.getId()).size());
        assertEquals(goalSnapshot(db, deck.getId()), goalSnapshot(db, other.getId()));

        // Restoring into the deck the backup came from (an "undo") must not double its history either.
        BackupRestoreResult undo = db.backup.importJsonBackup(json, deck.getId());
        assertEquals(0, undo.wordsInserted());
        assertEquals(0, undo.logsInserted());
        assertEquals(3, undo.duplicateLogsSkipped());
        assertEquals(3, db.logs.findByDeck(deck.getId()).size());
    }

    @Test
    void existingWordsKeepTheirScheduleUnlessOverwriteIsChosen() throws Exception {
        Db db = new Db(tempDir.resolve("overwrite.db"));
        Deck deck = db.decks.ensureDefaultDeck();
        seedTrickyDeck(db, deck.getId());
        Path json = db.backup.exportJsonBackup(deck.getId(), tempDir.resolve("backup.json"));
        WordCard saved = db.words.findByEnglish(deck.getId(), "aberrant").orElseThrow();

        Deck other = db.decks.create("Other");
        WordCard fresh = db.words.save(WordCard.createNew(other.getId(), "Aberrant", "反常的"));

        BackupRestoreResult kept = db.backup.importJsonBackup(json, other.getId());
        assertEquals(1, kept.wordsSkipped());
        assertEquals(0, kept.wordsUpdated());
        WordCard afterKeep = db.words.findById(fresh.getId()).orElseThrow();
        assertEquals(fresh.getNextReviewAt(), afterKeep.getNextReviewAt());
        assertEquals(0, afterKeep.getIntervalDays());
        assertEquals("反常的", afterKeep.getChinese());

        BackupRestoreResult overwritten = db.backup.importJsonBackup(json, other.getId(),
            BackupService.ExistingWordPolicy.OVERWRITE_SCHEDULE);
        assertEquals(1, overwritten.wordsUpdated());
        WordCard afterOverwrite = db.words.findById(fresh.getId()).orElseThrow();
        assertSameSchedule(saved, afterOverwrite);
        assertEquals("反常的", afterOverwrite.getChinese(), "only the schedule is replaced");

        BackupRestoreResult again = db.backup.importJsonBackup(json, other.getId(),
            BackupService.ExistingWordPolicy.OVERWRITE_SCHEDULE);
        assertEquals(0, again.wordsUpdated());
        assertEquals(4, again.wordsSkipped());
    }

    @Test
    void importsVersionOneBackupsWrittenByTheOldWriter() throws Exception {
        Db db = new Db(tempDir.resolve("v1.db"));
        Deck deck = db.decks.ensureDefaultDeck();
        // Exactly what the version 1 writer produced: only \, ", CR and LF escaped (the tab after
        // "tab" is a raw control character), every log value a string, logs keyed by wordEnglish.
        Path json = tempDir.resolve("v1.json");
        Files.writeString(json, """
            {
              "version": 1,
              "words": [
                {"english":"benign","chinese":"a. 良性的; [医] 良性的","phonetic":"","partOfSpeech":"adj.","exampleSentence":"a ] in {braces}","note":"raw\ttab and \\"quote\\" \\\\ back\\nline","tags":"gre"},
                {"english":"lucid","chinese":"清晰的","phonetic":"","partOfSpeech":"","exampleSentence":"","note":"","tags":""},
                {"english":"zeal","chinese":"热情","phonetic":"","partOfSpeech":"","exampleSentence":"","note":"","tags":""}
              ],
              "reviewLogs": [
                {"wordEnglish":"benign","reviewedAt":"2026-05-01T10:15:30.123456789","userAnswer":"良性]的","correctAnswer":"a. 良性的; [医] 良性的","similarity":"0.75","rating":"GOOD","elapsedMillis":"1200"},
                {"wordEnglish":"zeal","reviewedAt":"2026-05-02T08:00","userAnswer":"","correctAnswer":"热情","similarity":"0.0","rating":"AGAIN","elapsedMillis":"900"}
              ]
            }
            """, StandardCharsets.UTF_8);

        BackupRestoreResult result = db.backup.importJsonBackup(json, deck.getId());

        assertEquals(1, result.formatVersion());
        assertEquals(3, result.wordsInserted());
        assertEquals(2, result.logsInserted());
        assertTrue(result.invalidRows().isEmpty(), result.invalidRows().toString());
        WordCard benign = db.words.findByEnglish(deck.getId(), "benign").orElseThrow();
        assertEquals("a. 良性的; [医] 良性的", benign.getChinese());
        assertEquals("a ] in {braces}", benign.getExampleSentence());
        assertEquals("raw\ttab and \"quote\" \\ back\nline", benign.getNote());
        assertNull(benign.getPhonetic());
        // Version 1 saved no schedule, so the words start as new cards.
        assertEquals(NOW, benign.getNextReviewAt());
        assertEquals(WordCard.DEFAULT_EASINESS, benign.getEasinessFactor());
        ReviewLog log = db.logs.findByDeck(deck.getId()).get(0);
        assertEquals(benign.getId(), log.getWordId());
        assertEquals(LocalDateTime.of(2026, 5, 1, 10, 15, 30, 123456789), log.getReviewedAt());
        assertEquals(0.75, log.getSimilarity());
        assertEquals(1200, log.getElapsedMillis());

        // Files that omit "version" are read as version 1 too.
        Path unversioned = tempDir.resolve("unversioned.json");
        Files.writeString(unversioned, """
            {"words": [{"english":"candid","chinese":"坦率的","phonetic":"","partOfSpeech":"","exampleSentence":"","note":"","tags":""}],
             "reviewLogs": []}
            """, StandardCharsets.UTF_8);
        assertEquals(1, db.backup.importJsonBackup(unversioned, deck.getId()).wordsInserted());
    }

    @Test
    void failureMidRestoreLeavesTheDatabaseUnchanged() throws Exception {
        Db source = new Db(tempDir.resolve("source.db"));
        Deck sourceDeck = source.decks.ensureDefaultDeck();
        seedTrickyDeck(source, sourceDeck.getId());
        Path json = source.backup.exportJsonBackup(sourceDeck.getId(), tempDir.resolve("backup.json"));

        FailingReviewLogRepository[] failingLogs = new FailingReviewLogRepository[1];
        Db target = new Db(tempDir.resolve("target.db"),
            manager -> failingLogs[0] = new FailingReviewLogRepository(manager));
        Deck deck = target.decks.ensureDefaultDeck();
        WordCard existing = target.words.save(WordCard.createNew(deck.getId(), "aberrant", "反常的"));
        target.logs.insert(new ReviewLog(0, existing.getId(), NOW.minusDays(1), "x", "反常的", 0.0,
            ReviewRating.AGAIN, 500));
        target.goals.ensure(deck.getId(), TODAY, 20, 5, 10);
        target.goals.addProgress(deck.getId(), TODAY, 1, 0, 0, 3);
        List<WordCard> wordsBefore = target.words.findAllIncludingArchived(deck.getId());
        List<String> logsBefore = logSnapshot(target, deck.getId());
        List<String> goalsBefore = goalSnapshot(target, deck.getId());

        // Fail on the second review log, after the words have been written and the first log added.
        failingLogs[0].failOnInsert(2);
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> target.backup.importJsonBackup(
            json, deck.getId(), BackupService.ExistingWordPolicy.OVERWRITE_SCHEDULE));
        assertTrue(ErrorMessages.rootMessage(error).contains("simulated"), ErrorMessages.rootMessage(error));

        List<WordCard> wordsAfter = target.words.findAllIncludingArchived(deck.getId());
        assertEquals(wordsBefore.size(), wordsAfter.size());
        assertSameWord(wordsBefore.get(0), wordsAfter.get(0));
        assertEquals(logsBefore, logSnapshot(target, deck.getId()));
        assertEquals(goalsBefore, goalSnapshot(target, deck.getId()));
        assertTrue(target.achievements.findAll(deck.getId()).isEmpty());

        // Nothing was half-applied, so simply retrying succeeds.
        failingLogs[0].failOnInsert(0);
        BackupRestoreResult retry = target.backup.importJsonBackup(json, deck.getId(),
            BackupService.ExistingWordPolicy.OVERWRITE_SCHEDULE);
        assertEquals(3, retry.wordsInserted());
        assertEquals(1, retry.wordsUpdated());
        assertEquals(3, retry.logsInserted());
    }

    @Test
    void restoreBringsBackSavedGoalHistoryButAwardsNoXpOrNewWords() throws Exception {
        Db source = new Db(tempDir.resolve("source.db"));
        Deck sourceDeck = source.decks.ensureDefaultDeck();
        seedTrickyDeck(source, sourceDeck.getId());
        Path json = source.backup.exportJsonBackup(sourceDeck.getId(), tempDir.resolve("backup.json"));
        GoalService sourceGoals = new GoalService(source.goals, CLOCK);
        int savedXp = sourceGoals.totalXp(sourceDeck.getId());

        // A fresh install: the dashboard has already created an empty row for today.
        Db fresh = new Db(tempDir.resolve("fresh.db"));
        Deck freshDeck = fresh.decks.ensureDefaultDeck();
        GoalService freshGoals = new GoalService(fresh.goals, CLOCK);
        assertEquals(0, freshGoals.getTodayProgress(freshDeck.getId()).xpEarned());

        fresh.backup.importJsonBackup(json, freshDeck.getId());

        assertEquals(savedXp, freshGoals.totalXp(freshDeck.getId()), "saved XP comes back, nothing is added");
        assertEquals(sourceGoals.getTodayProgress(sourceDeck.getId()).newWordsCount(),
            freshGoals.getTodayProgress(freshDeck.getId()).newWordsCount());

        // A deck with its own progress today keeps it; only missing days are added, once.
        Db busy = new Db(tempDir.resolve("busy.db"));
        Deck busyDeck = busy.decks.ensureDefaultDeck();
        GoalService busyGoals = new GoalService(busy.goals, CLOCK);
        busyGoals.recordReview(busyDeck.getId(), ReviewRating.GOOD, 1.0);
        int todayXp = busyGoals.totalXp(busyDeck.getId());

        busy.backup.importJsonBackup(json, busyDeck.getId());
        busy.backup.importJsonBackup(json, busyDeck.getId());

        int pastXp = source.goals.findAll(sourceDeck.getId()).stream()
            .filter(row -> !row.date().equals(TODAY)).mapToInt(GoalRepository.GoalRow::xpEarned).sum();
        assertEquals(todayXp + pastXp, busyGoals.totalXp(busyDeck.getId()));
        assertEquals(0, busyGoals.getTodayProgress(busyDeck.getId()).newWordsCount());
        assertEquals(1, busyGoals.getTodayProgress(busyDeck.getId()).reviewedCount());
    }

    @Test
    void restoringAnEmptyGoalDayAgainReportsNothingRestored() throws Exception {
        Db db = new Db(tempDir.resolve("empty-day.db"));
        Deck deck = db.decks.ensureDefaultDeck();
        db.words.save(WordCard.createNew(deck.getId(), "lucid", "清晰的"));
        // Opening the dashboard creates an empty row for today, which the backup then contains.
        GoalService goals = new GoalService(db.goals, CLOCK);
        goals.getTodayProgress(deck.getId());
        Path json = db.backup.exportJsonBackup(deck.getId(), tempDir.resolve("backup.json"));
        Deck other = db.decks.create("Other");

        assertEquals(0, db.backup.importJsonBackup(json, deck.getId()).dailyGoalsRestored());
        assertEquals(1, db.backup.importJsonBackup(json, other.getId()).dailyGoalsRestored());
        assertEquals(0, db.backup.importJsonBackup(json, other.getId()).dailyGoalsRestored());
        assertEquals(goalSnapshot(db, deck.getId()), goalSnapshot(db, other.getId()));
    }

    @Test
    void invalidRowsAreSkippedAndReportedWithReasons() throws Exception {
        Db db = new Db(tempDir.resolve("invalid.db"));
        Deck deck = db.decks.ensureDefaultDeck();
        Path json = tempDir.resolve("invalid.json");
        Files.writeString(json, """
            {
              "format": "vocaboost-backup",
              "version": 2,
              "words": [
                {"english": "lucid", "chinese": "清晰的", "nextReviewAt": "2026-06-01T09:00:00", "intervalDays": 4},
                {"english": "", "chinese": "空"},
                {"english": "zeal", "chinese": "热情", "nextReviewAt": "tomorrow"},
                {"english": "LUCID", "chinese": "重复"},
                null
              ],
              "reviewLogs": [
                {"english": "lucid", "reviewedAt": "2026-05-20T09:00:00", "correctAnswer": "清晰的", "similarity": 1.0, "rating": "MEH"},
                {"english": "ghost", "reviewedAt": "2026-05-20T09:00:00", "correctAnswer": "鬼", "similarity": 1.0, "rating": "GOOD"},
                {"english": "lucid", "reviewedAt": "2026-05-21T09:00:00", "correctAnswer": "清晰的", "similarity": 1.0, "rating": "good"}
              ],
              "dailyGoals": [{"date": "28/05/2026", "reviewedCount": 3}],
              "achievements": [{"code": "first_review"}]
            }
            """, StandardCharsets.UTF_8);

        BackupRestoreResult result;
        try (LogCapture log = LogCapture.of(BackupService.class)) {
            result = db.backup.importJsonBackup(json, deck.getId());
            assertEquals(1, log.warnings().size());
        }

        assertEquals(1, result.wordsInserted());
        assertEquals(1, result.logsInserted());
        assertEquals(4, db.words.findByEnglish(deck.getId(), "lucid").orElseThrow().getIntervalDays());
        assertEquals(List.of(
            "Word #2: English word cannot be empty.",
            "Word #3 (zeal): invalid nextReviewAt \"tomorrow\"",
            "Word #4 (LUCID): the word appears more than once in the backup",
            "Word #5: not a JSON object",
            "Review log #1 (lucid): unknown rating \"MEH\"",
            "Review log #2 (ghost): no word \"ghost\" in the backup or the deck",
            "Daily goal #1 (28/05/2026): invalid date \"28/05/2026\"",
            "Achievement #1 (first_review): missing unlockedAt"
        ), result.invalidRows());
        assertTrue(result.toSummary().contains("Invalid rows skipped: 8"));
    }

    @Test
    void rejectsFilesThatAreNotSupportedBackups() throws Exception {
        Db db = new Db(tempDir.resolve("reject.db"));
        Deck deck = db.decks.ensureDefaultDeck();
        Path newer = tempDir.resolve("newer.json");
        Files.writeString(newer, "{\"format\":\"vocaboost-backup\",\"version\":3,\"words\":[]}");
        Path other = tempDir.resolve("other.json");
        Files.writeString(other, "{\"name\":\"something else\"}");
        Path broken = tempDir.resolve("broken.json");
        Files.writeString(broken, "{\"version\":2,\"words\":[{\"english\":");

        assertTrue(assertThrows(IllegalArgumentException.class, () -> db.backup.importJsonBackup(newer, deck.getId()))
            .getMessage().contains("Unsupported backup version 3"));
        assertThrows(IllegalArgumentException.class, () -> db.backup.importJsonBackup(other, deck.getId()));
        assertThrows(IllegalStateException.class, () -> db.backup.importJsonBackup(broken, deck.getId()));
        assertEquals(0, db.words.countAllInDatabase());
    }

    /** Four words (one archived) whose text breaks naive JSON handling, with logs, goals and an achievement. */
    private static void seedTrickyDeck(Db db, long deckId) throws SQLException {
        WordCard aberrant = card(deckId, "aberrant", "a. 异常的; [医] 畸变的 {x}");
        aberrant.setPhonetic("/æˈberənt/");
        aberrant.setPartOfSpeech("adj.");
        aberrant.setExampleSentence("Use {braces} here ] and [ \"quotes\" \\ \\u0041");
        aberrant.setNote(TRICKY_NOTE);
        aberrant.setTags("gre, [hard]");
        aberrant.setAddedAt(LocalDateTime.of(2026, 1, 3, 8, 30, 15, 120000000));
        aberrant.setLastReviewedAt(LocalDateTime.of(2026, 5, 20, 21, 4, 5, 987654321));
        aberrant.setNextReviewAt(LocalDateTime.of(2026, 6, 19, 21, 4, 5, 987654321));
        aberrant.setEasinessFactor(2.36);
        aberrant.setIntervalDays(30);
        aberrant.setRepetitions(5);
        aberrant.setConsecutiveCorrect(3);
        aberrant.setLapses(1);
        db.words.insert(aberrant);

        WordCard lucid = card(deckId, "lucid", "清晰的");
        db.words.insert(lucid);

        WordCard zeal = card(deckId, "zeal", "热情 }");
        zeal.setNote("{\"json\": [1, 2]}");
        zeal.setLastReviewedAt(LocalDateTime.of(2026, 4, 1, 7, 0));
        zeal.setNextReviewAt(LocalDateTime.of(2026, 4, 8, 7, 0));
        zeal.setEasinessFactor(1.3);
        zeal.setIntervalDays(7);
        zeal.setRepetitions(9);
        zeal.setLapses(4);
        zeal.setArchived(true);
        db.words.insert(zeal);

        WordCard obdurate = card(deckId, "obdurate", "[律] 顽固的");
        obdurate.setExampleSentence("tab\there, CR\rLF\n, and \\\"escaped\\\" text");
        db.words.insert(obdurate);

        db.logs.insert(new ReviewLog(0, aberrant.getId(), LocalDateTime.of(2026, 5, 1, 10, 15, 30, 123456789),
            "异常]的 \"x\"", "a. 异常的; [医] 畸变的 {x}", 0.8333333333333334, ReviewRating.GOOD, 4321));
        db.logs.insert(new ReviewLog(0, aberrant.getId(), LocalDateTime.of(2026, 5, 20, 21, 4, 5, 987654321),
            null, "a. 异常的; [医] 畸变的 {x}", 0.0, ReviewRating.AGAIN, 0));
        db.logs.insert(new ReviewLog(0, zeal.getId(), LocalDateTime.of(2026, 4, 1, 7, 0),
            "热情", "热情 }", 1.0, ReviewRating.EASY, 800));

        db.goals.ensure(deckId, TODAY.minusDays(3), 20, 5, 10);
        db.goals.addProgress(deckId, TODAY.minusDays(3), 25, 20, 6, 40);
        db.goals.markCompleted(deckId, TODAY.minusDays(3));
        db.goals.ensure(deckId, TODAY, 30, 8, 15);
        db.goals.addProgress(deckId, TODAY, 3, 2, 2, 12);

        db.achievements.insertIfAbsent(deckId, new Achievement("first_review", "First Review",
            "Completed [the] first {review}.", LocalDateTime.of(2026, 5, 1, 10, 15, 31), 10));
    }

    private static WordCard card(long deckId, String english, String chinese) {
        WordCard word = WordCard.createNew(deckId, english, chinese);
        word.setAddedAt(LocalDateTime.of(2026, 2, 1, 12, 0));
        word.setNextReviewAt(LocalDateTime.of(2026, 2, 1, 12, 0));
        return word;
    }

    private static Map<String, WordCard> byEnglish(List<WordCard> words) {
        return words.stream().collect(Collectors.toMap(WordCard::getEnglish, Function.identity()));
    }

    private static List<String> logSnapshot(Db db, long deckId) throws SQLException {
        Map<Long, String> english = db.words.findAllIncludingArchived(deckId).stream()
            .collect(Collectors.toMap(WordCard::getId, WordCard::getEnglish));
        return db.logs.findByDeck(deckId).stream()
            .map(log -> String.join("|", english.get(log.getWordId()), String.valueOf(log.getReviewedAt()),
                String.valueOf(log.getUserAnswer()), log.getCorrectAnswer(), String.valueOf(log.getSimilarity()),
                log.getRating().name(), String.valueOf(log.getElapsedMillis())))
            .toList();
    }

    private static List<String> goalSnapshot(Db db, long deckId) throws SQLException {
        return db.goals.findAll(deckId).stream()
            .map(row -> new GoalRepository.GoalRow(0, row.date(), row.reviewGoal(), row.newWordGoal(),
                row.sessionGoal(), row.reviewedCount(), row.correctCount(), row.newWordsCount(), row.xpEarned(),
                row.completed()).toString())
            .toList();
    }

    private static void assertSameWord(WordCard expected, WordCard actual) {
        assertEquals(expected.getEnglish(), actual.getEnglish());
        assertEquals(expected.getChinese(), actual.getChinese());
        assertEquals(expected.getPhonetic(), actual.getPhonetic());
        assertEquals(expected.getPartOfSpeech(), actual.getPartOfSpeech());
        assertEquals(expected.getExampleSentence(), actual.getExampleSentence());
        assertEquals(expected.getNote(), actual.getNote());
        assertEquals(expected.getTags(), actual.getTags());
        assertEquals(expected.getAddedAt(), actual.getAddedAt());
        assertEquals(expected.isArchived(), actual.isArchived());
        assertSameSchedule(expected, actual);
    }

    private static void assertSameSchedule(WordCard expected, WordCard actual) {
        assertEquals(expected.getLastReviewedAt(), actual.getLastReviewedAt());
        assertEquals(expected.getNextReviewAt(), actual.getNextReviewAt());
        assertEquals(expected.getEasinessFactor(), actual.getEasinessFactor());
        assertEquals(expected.getIntervalDays(), actual.getIntervalDays());
        assertEquals(expected.getRepetitions(), actual.getRepetitions());
        assertEquals(expected.getConsecutiveCorrect(), actual.getConsecutiveCorrect());
        assertEquals(expected.getLapses(), actual.getLapses());
    }

    /** One SQLite database with its repositories and a backup service on a fixed clock. */
    private final class Db {
        final DatabaseManager databaseManager;
        final DeckRepository decks;
        final WordRepository words;
        final ReviewLogRepository logs;
        final GoalRepository goals;
        final AchievementRepository achievements;
        final BackupService backup;

        Db(Path file) throws SQLException {
            this(file, ReviewLogRepository::new);
        }

        Db(Path file, Function<DatabaseManager, ReviewLogRepository> logRepository) throws SQLException {
            databaseManager = databases.open(file);
            decks = new DeckRepository(databaseManager);
            words = new WordRepository(databaseManager);
            logs = logRepository.apply(databaseManager);
            goals = new GoalRepository(databaseManager);
            achievements = new AchievementRepository(databaseManager);
            backup = new BackupService(decks, words, logs, goals, achievements, databaseManager,
                new WordValidationService(), CLOCK);
        }
    }

    private static final class FailingReviewLogRepository extends ReviewLogRepository {
        private int failOnInsertNumber;
        private int inserts;

        private FailingReviewLogRepository(DatabaseManager databaseManager) {
            super(databaseManager);
        }

        void failOnInsert(int insertNumber) {
            failOnInsertNumber = insertNumber;
            inserts = 0;
        }

        @Override
        public ReviewLog insert(ReviewLog log) throws SQLException {
            inserts++;
            if (failOnInsertNumber > 0 && inserts == failOnInsertNumber) {
                throw new SQLException("[SQLITE_IOERR] simulated disk error while restoring a review log");
            }
            return super.insert(log);
        }
    }

}
