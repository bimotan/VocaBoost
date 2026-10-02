package com.vocabtrainer.ui;

import com.vocabtrainer.service.DisplaySettings;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Control;
import javafx.scene.control.Dialog;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputDialog;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/**
 * The dialogs the desktop app shows: JavaFX alerts, prompts and file choosers that block until closed.
 * The dialogs use app.css and the text size the window has.
 */
public class JavaFxDialogs implements Dialogs {
    private final IntSupplier textSizePercent;

    /** Dialogs with text at the system's size. */
    public JavaFxDialogs() {
        this(() -> DisplaySettings.DEFAULT_TEXT_SIZE);
    }

    /** {@code textSizePercent} is the text size each dialog takes when it opens, as in AppStyle#applyTextSize. */
    public JavaFxDialogs(IntSupplier textSizePercent) {
        this.textSizePercent = textSizePercent;
    }

    @Override
    public void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message);
        style(alert);
        alert.showAndWait();
    }

    @Override
    public void showInfo(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Info");
        alert.setHeaderText(null);
        alert.setContentText(message);
        style(alert);
        alert.showAndWait();
    }

    @Override
    public void showText(String title, String header, String text, int rows) {
        TextArea content = new TextArea(text);
        content.setEditable(false);
        content.setWrapText(true);
        content.setPrefRowCount(rows);
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.getDialogPane().setContent(content);
        style(alert);
        alert.showAndWait();
    }

    @Override
    public boolean confirm(String title, String header, String content) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(title);
        confirm.setHeaderText(header);
        confirm.setContentText(content);
        style(confirm);
        Optional<ButtonType> result = confirm.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
    }

    @Override
    public Optional<ButtonType> choose(String title, String header, String content, ButtonType... buttons) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "", buttons);
        confirm.setTitle(title);
        confirm.setHeaderText(header);
        confirm.setContentText(content);
        style(confirm);
        return confirm.showAndWait();
    }

    @Override
    public Optional<String> askText(String title, String header, String content, String initialValue) {
        TextInputDialog dialog = new TextInputDialog(initialValue);
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        dialog.setContentText(content);
        style(dialog);
        return dialog.showAndWait();
    }

    @Override
    public boolean showForm(String title, Node form, BooleanSupplier onOk) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        // OK closes the dialog only when onOk accepts the input; otherwise it stays open as it is.
        Node okButton = dialog.getDialogPane().lookupButton(ButtonType.OK);
        okButton.addEventFilter(ActionEvent.ACTION, event -> {
            if (!onOk.getAsBoolean()) {
                event.consume();
            }
        });
        style(dialog);
        // The keyboard starts in the form's first field, not on OK, which takes the focus as the dialog shows.
        dialog.setOnShown(event -> Platform.runLater(() -> firstInput(form).ifPresent(Node::requestFocus)));
        Optional<ButtonType> result = dialog.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
    }

    /** Gives the dialog's window the app's stylesheet and text size, as the main window has. */
    private void style(Dialog<?> dialog) {
        Scene scene = dialog.getDialogPane().getScene();
        AppStyle.install(scene);
        AppStyle.applyTextSize(scene.getRoot(), textSizePercent.getAsInt());
    }

    /** The first enabled, focusable control in {@code node}, depth first. */
    private static Optional<Node> firstInput(Node node) {
        if (node instanceof Control control) {
            return control.isFocusTraversable() && !control.isDisabled() && control.isVisible()
                ? Optional.of(control) : Optional.empty();
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Optional<Node> input = firstInput(child);
                if (input.isPresent()) {
                    return input;
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<Path> chooseOpenFile(Window owner, String title, List<FileChooser.ExtensionFilter> filters) {
        File file = fileChooser(title, filters).showOpenDialog(owner);
        return Optional.ofNullable(file).map(File::toPath);
    }

    @Override
    public Optional<Path> chooseSaveFile(Window owner, String title, String initialFileName,
                                         List<FileChooser.ExtensionFilter> filters) {
        FileChooser chooser = fileChooser(title, filters);
        chooser.setInitialFileName(initialFileName);
        File file = chooser.showSaveDialog(owner);
        return Optional.ofNullable(file).map(File::toPath);
    }

    private FileChooser fileChooser(String title, List<FileChooser.ExtensionFilter> filters) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().addAll(filters);
        return chooser;
    }
}
