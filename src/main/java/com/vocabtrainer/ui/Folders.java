package com.vocabtrainer.ui;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Opens folders, such as the data folder, in the system's file manager. */
public final class Folders {
    private static final Logger LOGGER = Logger.getLogger(Folders.class.getName());

    private Folders() {
    }

    /**
     * Opens {@code folder}; where the system cannot open folders, or opening fails, its path is
     * shown instead. {@code name} names it in messages, e.g. "Data folder".
     */
    public static void open(UiErrors errors, String name, Path folder) {
        if (folder == null) {
            errors.showInfo(name + " is unavailable.");
            return;
        }
        try {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                errors.showInfo(name + ": " + folder);
                return;
            }
            Desktop.getDesktop().open(folder.toFile());
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Cannot open " + folder, e);
            errors.showError("Open folder failed", UiErrors.rootMessage(e) + System.lineSeparator() + name + ": " + folder);
        }
    }
}
