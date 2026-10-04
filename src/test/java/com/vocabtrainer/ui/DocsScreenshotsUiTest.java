package com.vocabtrainer.ui;

import com.vocabtrainer.domain.WordCard;
import javafx.scene.Node;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Separator;
import javafx.scene.control.TableColumn;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Takes the screenshots in docs/screenshots from the real window, in English and in Simplified
 * Chinese, after three study days of reviews on the starter words and with an exam date set. With
 * -Pui-tests they land in target/ui-snapshots, which keeps this tour working; to update the docs, run
 * it with the screenshots profile: {@code xvfb-run -a -s "-screen 0 1400x900x24" ./mvnw test -Pscreenshots}.
 */
@Tag("ui")
class DocsScreenshotsUiTest extends MainWindowUiTest {
    private static final Path OUTPUT = Path.of(System.getProperty("vocaboost.screenshots.dir", SNAPSHOT_DIR.toString()));
    /** Lets the window finish drawing (charts, progress bars) before a picture is taken. */
    private static final long SETTLE_MILLIS = 700;
    private static final int STUDY_DAYS = 3;
    private static final int CARDS_PER_DAY = 12;
    private static final int DAYS_TO_EXAM = 10;
    /** English, Chinese, Next review, Interval, ... */
    private static final int INTERVAL_COLUMN = 3;
    /** Study, offline mode and ECDICT come before the AI section, each followed by a separator. */
    private static final int SEPARATORS_ABOVE_AI = 3;

    @Override
    Locale systemLocale() {
        return testName().startsWith("takeTheChinese") ? Locale.SIMPLIFIED_CHINESE : super.systemLocale();
    }

    @Test
    void takeTheDocsScreenshots() throws Exception {
        takeScreenshots("");
    }

    @Test
    void takeTheChineseDocsScreenshots() throws Exception {
        takeScreenshots("zh-");
    }

    private void takeScreenshots(String prefix) throws Exception {
        studyForDays();
        setExamDate();

        // The next card answered, with the feedback, the word's details and the rating buttons' intervals.
        selectTab("reviewTab");
        WordCard word = questionWord();
        type("answerField", correctAnswer(word));
        click("submitAnswerButton");
        save(prefix + "review.png");

        selectTab("dashboardTab");
        save(prefix + "dashboard.png");
        selectTab("statisticsTab");
        save(prefix + "statistics.png");
        selectTab("wordListTab");
        showLongestIntervalsFirst();
        save(prefix + "word-list.png");
        selectTab("settingsTab");
        save(prefix + "settings.png", this::settingsBottom);
    }

    /** A few sessions on consecutive study days: mostly right answers, with a miss now and then. */
    private void studyForDays() throws SQLException {
        for (int day = 1; day <= STUDY_DAYS; day++) {
            if (day > 1) {
                clock.advance(Duration.ofDays(1));
            }
            selectTab("reviewTab");
            click("startSessionButton");
            for (int card = 1; card <= CARDS_PER_DAY; card++) {
                boolean right = card % 4 != 0;
                review(right, right ? "rateGoodButton" : "rateAgainButton");
            }
        }
    }

    /** Every deck's exam, a few days ahead, through the Dashboard's form. */
    private void setExamDate() {
        selectTab("dashboardTab");
        LocalDate exam = services.reviewScheduler().studyDay().of(clock.now()).plusDays(DAYS_TO_EXAM);
        dialogs.submitForm(form -> datePicker(form).getEditor().setText(exam.toString()));
        click("editExamButton");
    }

    /** Sorts the Word List by interval, longest first, so the reviewed words are on top, and selects the first. */
    private void showLongestIntervalsFirst() {
        Fx.run(() -> {
            var table = this.<WordCard>table("wordTable");
            TableColumn<WordCard, ?> interval = table.getColumns().get(INTERVAL_COLUMN);
            interval.setSortType(TableColumn.SortType.DESCENDING);
            table.getSortOrder().setAll(List.of(interval));
            table.getSelectionModel().select(0);
            table.scrollTo(0);
        });
    }

    private static DatePicker datePicker(Node form) {
        Node node = form.lookup("#examDatePicker");
        if (!(node instanceof DatePicker picker)) {
            throw new AssertionError("No date picker in the exam form: " + node);
        }
        return picker;
    }

    /**
     * Where the Settings screenshot ends: just below the separator above the AI section, so that no
     * section is cut off by the bottom of the window.
     */
    private int settingsBottom() {
        return Fx.call(() -> {
            Node separator = find("settingsContent", VBox.class).getChildren().stream()
                .filter(Separator.class::isInstance)
                .skip(SEPARATORS_ABOVE_AI - 1)
                .findFirst()
                .orElseThrow(() -> new AssertionError("The Settings tab has fewer sections"));
            return (int) Math.ceil(separator.localToScene(separator.getBoundsInLocal()).getMaxY()) + 6;
        });
    }

    private void save(String fileName) throws IOException, InterruptedException {
        save(fileName, () -> Integer.MAX_VALUE);
    }

    /**
     * Saves a snapshot of the window once it has settled, cut off at the height that
     * {@code bottomOfImage} gives then when the window is taller.
     */
    private void save(String fileName, IntSupplier bottomOfImage) throws IOException, InterruptedException {
        waitForBackgroundTasks();
        Thread.sleep(SETTLE_MILLIS);
        Fx.flush();
        int bottom = bottomOfImage.getAsInt();
        Path snapshot = snapshot(fileName.replace(".png", ""));
        Files.createDirectories(OUTPUT);
        Path target = OUTPUT.resolve(fileName);
        BufferedImage image = ImageIO.read(snapshot.toFile());
        if (bottom < image.getHeight()) {
            ImageIO.write(image.getSubimage(0, 0, image.getWidth(), bottom), "png", target.toFile());
        } else {
            Files.copy(snapshot, target, StandardCopyOption.REPLACE_EXISTING);
        }
        assertTrue(Files.size(target) > 0, target.toString());
    }
}
