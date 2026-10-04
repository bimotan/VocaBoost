package com.vocabtrainer.ui;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.ReviewLog;
import com.vocabtrainer.domain.WordCard;
import javafx.scene.chart.PieChart;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("ui")
class StatisticsAndBackupUiTest extends MainWindowUiTest {
    @Test
    void theStatisticsTabShowsTodaysReviewsAndTheHardestWord() throws Exception {
        review(true, "rateGoodButton");
        WordCard missed = review(false, "rateAgainButton");
        long deckId = currentDeck().getId();

        selectTab("statisticsTab");

        List<ChartPoint> reviewCounts = chartPoints("reviewCountChart");
        // The study day, which starts at the rollover hour: before 4 am it is still yesterday.
        LocalDate today = services.reviewScheduler().studyDay().of(LocalDateTime.now());
        assertEquals(7, reviewCounts.size());
        assertEquals(new ChartPoint(today.getMonthValue() + "/" + today.getDayOfMonth(), 2), reviewCounts.get(6));
        assertTrue(reviewCounts.subList(0, 6).stream().allMatch(point -> point.y() == 0), reviewCounts.toString());
        List<ChartPoint> accuracy = chartPoints("accuracyChart");
        assertEquals(7, accuracy.size());
        assertEquals(0.5, accuracy.get(6).y(), 1e-9);
        int memoryWords = Fx.call(() -> find("memoryChart", PieChart.class).getData().stream()
            .mapToInt(slice -> (int) slice.getPieValue()).sum());
        assertEquals(STARTER_WORDS, memoryWords);
        assertEquals("Overdue or due words: " + services.statsService().overdueCount(deckId), text("overdueStatsLabel"));
        assertTrue(text("hardestWordsArea").startsWith(missed.getEnglish() + " | avg similarity "),
            text("hardestWordsArea"));
        assertEquals(2, text("hardestWordsArea").lines().count());
        assertFalse(text("analyticsArea").isBlank());
        snapshot("after-two-reviews");

        // Coming back to Statistics after another review shows it.
        review(true, "rateGoodButton");
        selectTab("statisticsTab");
        assertEquals(3, chartPoints("reviewCountChart").get(6).y());
    }

    @Test
    void aJsonBackupCanBeExportedAndRestoredIntoAnotherDeck() throws Exception {
        WordCard reviewed = review(true, "rateGoodButton");
        selectTab("statisticsTab");
        Path backup = tempDir.resolve("backup.json");

        dialogs.saveFile(backup);
        click("exportBackupButton");

        ScriptedDialogs.Shown chooser = dialogs.last(ScriptedDialogs.Kind.SAVE_FILE);
        assertEquals("Export JSON backup", chooser.title());
        assertEquals("vocaboost-backup.json", chooser.value());
        waitForBackgroundTasks();
        assertEquals("Exported: " + backup.toAbsolutePath(), dialogs.last(ScriptedDialogs.Kind.INFO).content());
        assertTrue(Files.size(backup) > 0);

        dialogs.answerText("Restored");
        click("newDeckButton");
        Deck restored = currentDeck();
        assertEquals("Restored", restored.getName());
        selectTab("dashboardTab");
        assertEquals("0", text("totalWordsLabel"));

        selectTab("statisticsTab");
        dialogs.openFile(backup).chooseButton("Keep current progress");
        click("importBackupButton");

        ScriptedDialogs.Shown question = dialogs.last(ScriptedDialogs.Kind.CHOOSE);
        assertEquals("Restore the backup into Restored?", question.header());
        assertEquals("Keep current progress | Use backup progress | Restore into a new deck | Cancel", question.value());
        waitForBackgroundTasks();
        ScriptedDialogs.Shown summary = dialogs.last(ScriptedDialogs.Kind.TEXT);
        assertEquals("Import JSON backup", summary.title());
        assertEquals("Deck: Restored", summary.header());
        assertTrue(summary.content().startsWith("Backup restored (format version "), summary.content());
        assertTrue(summary.content().contains("Words: " + STARTER_WORDS + " added, 0 updated"), summary.content());
        assertTrue(summary.content().contains("Review logs: 1 added, 0 already present."), summary.content());

        selectTab("dashboardTab");
        assertEquals(String.valueOf(STARTER_WORDS), text("totalWordsLabel"));
        selectTab("wordListTab");
        assertEquals(STARTER_WORDS, rowCount("wordTable"));
        List<ReviewLog> logs = services.reviewLogRepository().findByDeck(restored.getId());
        assertEquals(1, logs.size());
        WordCard restoredWord = services.wordRepository().findByEnglish(restored.getId(), reviewed.getEnglish()).orElseThrow();
        assertEquals(1, restoredWord.getRepetitions());
    }

    @Test
    void cancellingTheBackupFileChoosersDoesNothing() {
        selectTab("statisticsTab");
        dialogs.cancelOpenFile();
        click("importBackupButton");
        assertEquals("Import JSON backup", dialogs.last(ScriptedDialogs.Kind.OPEN_FILE).title());

        dialogs.openFile(tempDir.resolve("unused.json")).chooseButton("Cancel");
        click("importBackupButton");

        waitForBackgroundTasks();
        assertFalse(dialogs.wasShown(ScriptedDialogs.Kind.TEXT));
        selectTab("dashboardTab");
        assertEquals(String.valueOf(STARTER_WORDS), text("totalWordsLabel"));
    }

    @Test
    void wordsAndReviewLogsCanBeExportedAsCsv() throws Exception {
        review(true, "rateGoodButton");
        selectTab("statisticsTab");
        Path words = tempDir.resolve("words.csv");
        Path logs = tempDir.resolve("logs.csv");

        dialogs.saveFile(words);
        click("exportWordsCsvButton");
        waitForBackgroundTasks();
        dialogs.saveFile(logs);
        click("exportReviewLogsCsvButton");
        waitForBackgroundTasks();

        List<ScriptedDialogs.Shown> infos = dialogs.shown(ScriptedDialogs.Kind.INFO);
        assertEquals(List.of("Exported: " + words.toAbsolutePath(), "Exported: " + logs.toAbsolutePath()),
            infos.stream().map(ScriptedDialogs.Shown::content).toList());
        assertEquals(STARTER_WORDS + 1, Files.readAllLines(words).size());
        assertEquals(2, Files.readAllLines(logs).size());
    }

    @Test
    void anExportedWordsCsvImportsIntoANewDeckWithEveryField() throws Exception {
        long starterDeckId = currentDeck().getId();
        WordCard lucid = services.wordRepository().findByEnglish(starterDeckId, "lucid").orElseThrow();
        lucid.setPhonetic("/ˈluːsɪd/");
        lucid.setNote("=HYPERLINK(\"http://x\",\"点击\")");
        services.wordRepository().update(lucid);
        selectTab("statisticsTab");
        Path words = tempDir.resolve("words.csv");
        dialogs.saveFile(words);
        click("exportWordsCsvButton");
        waitForBackgroundTasks();
        assertTrue(Files.readString(words).startsWith("\uFEFFenglish,chinese,phonetic,pos,example,note,tags\r\n"));

        dialogs.answerText("Copy");
        click("newDeckButton");
        selectTab("addImportTab");
        dialogs.openFile(words);
        click("chooseImportFileButton");
        click("importCsvButton");
        waitForBackgroundTasks();

        assertTrue(text("importStatusLabel").startsWith("Deck: Copy" + System.lineSeparator()
            + "Imported " + STARTER_WORDS + ", skipped 0."), text("importStatusLabel"));
        assertEquals(wordFields(starterDeckId), wordFields(currentDeck().getId()));
        selectTab("wordListTab");
        assertEquals(STARTER_WORDS, rowCount("wordTable"));
    }

    private List<List<String>> wordFields(long deckId) throws Exception {
        return services.wordRepository().findAll(deckId).stream()
            .map(word -> List.of(word.getEnglish(), word.getChinese(), String.valueOf(word.getPhonetic()),
                String.valueOf(word.getPartOfSpeech()), String.valueOf(word.getExampleSentence()),
                String.valueOf(word.getNote()), String.valueOf(word.getTags())))
            .toList();
    }
}
