package com.vocabtrainer.ui;

import com.vocabtrainer.domain.WordCard;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Takes the screenshots in docs/screenshots from the real window after a short review session on
 * the starter words. With -Pui-tests they land in target/ui-snapshots, which keeps this tour working;
 * to update the docs, run it with the screenshots profile:
 * {@code xvfb-run -a -s "-screen 0 1400x900x24" ./mvnw test -Pscreenshots}.
 */
@Tag("ui")
class DocsScreenshotsUiTest extends MainWindowUiTest {
    private static final Path OUTPUT = Path.of(System.getProperty("vocaboost.screenshots.dir", SNAPSHOT_DIR.toString()));
    /** Lets the window finish drawing (charts, progress bars) before a picture is taken. */
    private static final long SETTLE_MILLIS = 700;

    @Test
    void takeTheDocsScreenshots() throws Exception {
        selectTab("reviewTab");
        click("startSessionButton");
        // Mostly right answers, with a miss now and then.
        for (int card = 1; card <= 8; card++) {
            boolean right = card % 4 != 0;
            review(right, right ? "rateGoodButton" : "rateAgainButton");
        }
        // The next card answered, with the feedback and the rating buttons showing.
        WordCard word = questionWord();
        type("answerField", correctAnswer(word));
        click("submitAnswerButton");
        save("review.png");

        selectTab("dashboardTab");
        save("dashboard.png");
        selectTab("statisticsTab");
        save("statistics.png");
        selectTab("wordListTab");
        save("word-list.png");
    }

    private void save(String fileName) throws IOException, InterruptedException {
        waitForBackgroundTasks();
        Thread.sleep(SETTLE_MILLIS);
        Fx.flush();
        Path snapshot = snapshot(fileName.replace(".png", ""));
        Files.createDirectories(OUTPUT);
        Path target = OUTPUT.resolve(fileName);
        Files.copy(snapshot, target, StandardCopyOption.REPLACE_EXISTING);
        assertTrue(Files.size(target) > 0, target.toString());
    }
}
