package com.vocabtrainer.ui;

import javafx.scene.Parent;
import javafx.scene.Scene;

import java.util.List;
import java.util.Objects;

/**
 * The app's stylesheet, {@code app.css}, and its text sizes. The views give their nodes style
 * classes instead of inline styles; the stylesheet sizes text in em, so the text size the Settings
 * tab chooses, applied to a scene's root, scales all of it.
 */
public final class AppStyle {
    /** The stylesheet's URL, for a scene's or a dialog's stylesheets. */
    public static final String STYLESHEET =
        Objects.requireNonNull(AppStyle.class.getResource("app.css"), "app.css is missing").toExternalForm();

    private static final List<String> TEXT_SIZE_CLASSES = List.of("text-size-115", "text-size-130");

    private AppStyle() {
    }

    /** Styles {@code scene} with the app's stylesheet. */
    public static void install(Scene scene) {
        if (!scene.getStylesheets().contains(STYLESHEET)) {
            scene.getStylesheets().add(STYLESHEET);
        }
    }

    /**
     * Shows the text under {@code root} at {@code percent} of the system's text size: 100, 115 or 130
     * (see {@link com.vocabtrainer.service.DisplaySettings}); any other value shows it at 100%.
     */
    public static void applyTextSize(Parent root, int percent) {
        root.getStyleClass().removeAll(TEXT_SIZE_CLASSES);
        String styleClass = "text-size-" + percent;
        if (TEXT_SIZE_CLASSES.contains(styleClass)) {
            root.getStyleClass().add(styleClass);
        }
    }
}
