package com.vocabtrainer.ui;

import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Every modal dialog and file chooser the main window opens. {@link JavaFxDialogs} shows the real
 * JavaFX dialogs; UI tests pass a scripted implementation, because a blocking showAndWait would
 * hang them.
 */
public interface Dialogs {
    /** An error alert whose title and header are both {@code title}. */
    void showError(String title, String message);

    /** An information alert titled "Info" without a header. */
    void showInfo(String message);

    /** An information alert that shows {@code text} in a read-only, wrapping text area. */
    void showText(String title, String header, String text, int rows);

    /** An OK / Cancel confirmation; true only when OK is chosen. */
    boolean confirm(String title, String header, String content);

    /** A confirmation with the given buttons; empty when it is closed without a choice. */
    Optional<ButtonType> choose(String title, String header, String content, ButtonType... buttons);

    /** A text prompt starting with {@code initialValue}; empty when it is cancelled. */
    Optional<String> askText(String title, String header, String content, String initialValue);

    /** An OK / Cancel dialog around {@code form}; true only when OK is chosen. */
    boolean showForm(String title, Node form);

    /** A file-open chooser; empty when it is cancelled. */
    Optional<Path> chooseOpenFile(Window owner, String title, List<FileChooser.ExtensionFilter> filters);

    /** A file-save chooser; empty when it is cancelled. */
    Optional<Path> chooseSaveFile(Window owner, String title, String initialFileName,
                                  List<FileChooser.ExtensionFilter> filters);
}
