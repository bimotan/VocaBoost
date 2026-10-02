package com.vocabtrainer.ui;

import javafx.geometry.Dimension2D;
import javafx.geometry.Rectangle2D;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowSizeTest {
    @Test
    void aLargeScreenGetsTheUsualSize() {
        Rectangle2D fullHd = new Rectangle2D(0, 0, 1920, 1040);
        assertEquals(new Dimension2D(1120, 780), WindowSize.initialSceneSize(fullHd));
        assertEquals(new Dimension2D(960, 640), WindowSize.minimumWindowSize(fullHd));
    }

    @Test
    void theWindowFitsA1366x768LaptopAndA1080pOneAt150Percent() {
        // The usable areas without a 40 px taskbar.
        for (Rectangle2D laptop : new Rectangle2D[] {new Rectangle2D(0, 0, 1366, 728), new Rectangle2D(0, 0, 1280, 680)}) {
            Dimension2D scene = WindowSize.initialSceneSize(laptop);
            assertTrue(scene.getHeight() + WindowSize.DECORATIONS <= laptop.getHeight(), laptop + " " + scene);
            assertTrue(scene.getWidth() <= laptop.getWidth(), laptop + " " + scene);
            Dimension2D minimum = WindowSize.minimumWindowSize(laptop);
            assertTrue(minimum.getHeight() <= laptop.getHeight() && minimum.getWidth() <= laptop.getWidth(),
                laptop + " " + minimum);
        }
    }

    @Test
    void theMinimumNeverExceedsATinyScreen() {
        Rectangle2D netbook = new Rectangle2D(0, 0, 1024, 560);
        assertEquals(new Dimension2D(960, 560), WindowSize.minimumWindowSize(netbook));
        assertEquals(new Dimension2D(1024, 520), WindowSize.initialSceneSize(netbook));
    }
}
