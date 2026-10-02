package com.vocabtrainer.ui;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Goals, XP and the streak on the Dashboard: importing earns nothing, a word counts as new on its
 * first review, and the Dashboard and the Statistics tab agree (review findings E8 and E10).
 */
class GoalsUiTest extends MainWindowUiTest {
    @Test
    void importingTwoThousandWordsEarnsNoXpAndNoNewWords() throws Exception {
        Path csv = tempDir.resolve("gre-2000.csv");
        List<String> lines = new ArrayList<>(List.of("english,chinese"));
        for (int i = 0; i < 2000; i++) {
            lines.add("word" + (char) ('a' + i / 676) + (char) ('a' + i / 26 % 26) + (char) ('a' + i % 26) + ",释义");
        }
        Files.write(csv, lines, StandardCharsets.UTF_8);

        selectTab("addImportTab");
        type("importPathField", csv.toString());
        click("importCsvButton");
        waitForBackgroundTasks();
        assertTrue(text("importStatusLabel").contains("Imported 2000"), text("importStatusLabel"));
        assertFalse(text("importStatusLabel").contains("Unlocked"), "no badges for importing");

        selectTab("dashboardTab");
        assertEquals(String.valueOf(STARTER_WORDS + 2000), text("totalWordsLabel"));
        assertEquals("0 / 5", text("newWordsTodayLabel"));
        assertEquals("0", text("xpLabel"));
        assertEquals("None yet", text("badgesLabel"));
    }

    @Test
    void aWordIsNewOnItsFirstReviewAndTheDashboardAgreesWithTheStatistics() throws Exception {
        review(true, "rateGoodButton");

        selectTab("dashboardTab");
        assertEquals("1 / 5", text("newWordsTodayLabel"), "the first review of a new word");
        assertEquals("1 / 20", text("reviewedTodayLabel"));
        assertEquals("100%", text("accuracyTodayLabel"));
        assertEquals("1 days", text("streakLabel"));
        selectTab("statisticsTab");
        List<ChartPoint> reviews = chartPoints("reviewCountChart");
        assertEquals(1.0, reviews.get(reviews.size() - 1).y(), "today's bar is the dashboard's count");
        List<ChartPoint> accuracy = chartPoints("accuracyChart");
        assertEquals(1.0, accuracy.get(accuracy.size() - 1).y());
    }
}
