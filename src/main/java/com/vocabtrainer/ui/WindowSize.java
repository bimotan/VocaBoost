package com.vocabtrainer.ui;

import javafx.geometry.Dimension2D;
import javafx.geometry.Rectangle2D;

/**
 * The main window's size on a screen. It opens at 1120 x 780, smaller on a screen whose usable area
 * (without the taskbar) is smaller, and can be made as small as 960 x 640: a 1366 x 768 laptop, or a
 * 1920 x 1080 one at 150%, has about 1366 x 728 or 1280 x 680 to offer. The tabs scroll when their
 * content needs more height, and the Review tab keeps its rating buttons in view.
 */
public final class WindowSize {
    public static final double MIN_WIDTH = 960;
    public static final double MIN_HEIGHT = 640;
    static final double PREFERRED_WIDTH = 1120;
    static final double PREFERRED_HEIGHT = 780;
    /** Room for the title bar and borders, which a scene's size does not include. */
    static final double DECORATIONS = 40;

    private WindowSize() {
    }

    /** The size of the window's content (its scene) when it opens on a screen with this usable area. */
    public static Dimension2D initialSceneSize(Rectangle2D usableArea) {
        return new Dimension2D(Math.min(PREFERRED_WIDTH, usableArea.getWidth()),
            Math.min(PREFERRED_HEIGHT, usableArea.getHeight() - DECORATIONS));
    }

    /** The smallest the window can be made, title bar included; never more than the screen offers. */
    public static Dimension2D minimumWindowSize(Rectangle2D usableArea) {
        return new Dimension2D(Math.min(MIN_WIDTH, usableArea.getWidth()), Math.min(MIN_HEIGHT, usableArea.getHeight()));
    }
}
