package com.vocabtrainer.ui;

import com.vocabtrainer.service.DisplaySettings;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.text.Font;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The window fits small laptop screens and its text can be made larger. */
@Tag("ui")
class WindowUiTest extends MainWindowUiTest {
    private static final List<String> RATING_BUTTONS =
        List.of("rateAgainButton", "rateHardButton", "rateGoodButton", "rateEasyButton", "overrideButton");

    @Test
    void theWindowCanBeMadeAsSmallAs960x640() {
        double[] minimum = minimumWindowSize();
        assertEquals(WindowSize.MIN_WIDTH, minimum[0]);
        assertEquals(WindowSize.MIN_HEIGHT, minimum[1]);
        assertTrue(minimum[1] <= 728, "a 1366 x 768 screen has about 728 px above the taskbar");
    }

    @Test
    void theWindowHasTheAppIconInEverySize() {
        List<Image> icons = windowIcons();

        assertEquals(List.of(16, 32, 48, 64, 128, 256, 512),
            icons.stream().map(icon -> (int) icon.getWidth()).toList());
        for (Image icon : icons) {
            assertFalse(icon.isError(), icon.getUrl());
            assertEquals(icon.getWidth(), icon.getHeight(), icon.getUrl());
            // Transparent corners around the rounded square, the V's white in the middle.
            int size = (int) icon.getWidth();
            assertEquals(0, Fx.call(() -> icon.getPixelReader().getArgb(0, 0)) >>> 24, icon.getUrl());
            assertEquals(0xFFFFFFFF, (int) Fx.call(() -> icon.getPixelReader().getArgb(size / 2, size * 2 / 3)),
                icon.getUrl());
        }
    }

    @Test
    void theRatingButtonsAreInViewOnA1366x768LaptopAndAtTheSmallestSize() throws Exception {
        for (double[] size : new double[][] {{1366, 768}, {1366, 728}, {WindowSize.MIN_WIDTH, WindowSize.MIN_HEIGHT}}) {
            resizeWindow(size[0], size[1]);
            answerACard();
            for (String button : RATING_BUTTONS) {
                assertTrue(isEntirelyInWindow(button), button + " at " + size[0] + " x " + size[1]);
            }
            assertTrue(isEntirelyInWindow("answerField"), "at " + size[0] + " x " + size[1]);
            snapshot("answered-" + (int) size[0] + "x" + (int) size[1]);
            click("rateGoodButton");
        }
    }

    @Test
    void largerTextScrollsTheReviewTabButKeepsTheRatingButtonsInView() throws Exception {
        selectTab("settingsTab");
        selectTextSize(130);
        resizeWindow(WindowSize.MIN_WIDTH, WindowSize.MIN_HEIGHT);

        answerACard();

        for (String button : RATING_BUTTONS) {
            assertTrue(isEntirelyInWindow(button), button);
        }
        snapshot("answered-130-percent");
        // The content above the buttons is taller than the window: it scrolls.
        assertTrue(Fx.call(() -> {
            ScrollPane scroll = (ScrollPane) tab("reviewTab").getContent().lookup(".tab-scroll");
            return scroll.getContent().getLayoutBounds().getHeight() > scroll.getViewportBounds().getHeight();
        }));
    }

    @Test
    void theTextSizeSettingScalesTheWindowsTextAndIsKept() {
        double defaultSize = Fx.call(() -> Font.getDefault().getSize());
        double question100 = questionFontSize();
        double header100 = Fx.call(() -> find("headerSubtitleLabel", Label.class).getFont().getSize());
        assertEquals(defaultSize, header100, 0.01, "100% is the system's text size");
        assertEquals(34.0 / 13.0, question100 / defaultSize, 0.01, "the question is as large as before app.css");

        selectTab("settingsTab");
        selectTextSize(130);

        assertEquals(130, new DisplaySettings(services.settingsService()).textSizePercent());
        assertEquals(header100 * 1.3, Fx.call(() -> find("headerSubtitleLabel", Label.class).getFont().getSize()), 0.1);
        assertEquals(question100 * 1.3, questionFontSize(), 0.1);

        restartApp();

        assertEquals("130% ", Fx.call(() -> this.<Integer>comboBox("textSizeSelector").getValue() + "% "));
        assertEquals(question100 * 1.3, questionFontSize(), 0.1, "the size is kept");
        selectTab("settingsTab");
        selectTextSize(100);
        assertEquals(question100, questionFontSize(), 0.1);
        assertFalse(dialogs.wasShown(ScriptedDialogs.Kind.ERROR));
    }

    private void selectTextSize(int percent) {
        this.<Integer>select("textSizeSelector", size -> size == percent);
        Fx.run(() -> find("textSizeSelector", Node.class).getScene().getRoot().applyCss());
    }

    private double questionFontSize() {
        return Fx.call(() -> {
            Label question = find("reviewWordLabel", Label.class);
            question.applyCss();
            return question.getFont().getSize();
        });
    }

    /** Submits an answer to the next card, so the result, the details card and the rating buttons show. */
    private void answerACard() throws Exception {
        selectTab("reviewTab");
        type("answerField", correctAnswer(questionWord()));
        click("submitAnswerButton");
        waitForBackgroundTasks();
        Fx.run(() -> {
            Node root = find("ratingButtons", Node.class).getScene().getRoot();
            root.applyCss();
            ((Parent) root).layout();
        });
        assertFalse(isDisabled("ratingButtons"));
    }
}
