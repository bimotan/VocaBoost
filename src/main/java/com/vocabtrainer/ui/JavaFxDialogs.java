package com.vocabtrainer.ui;

import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputDialog;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** The dialogs the desktop app shows: JavaFX alerts, prompts and file choosers that block until closed. */
public class JavaFxDialogs implements Dialogs {
    @Override
    public void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message);
        alert.showAndWait();
    }

    @Override
    public void showInfo(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Info");
        alert.setHeaderText(null);
        alert.setContentText(message);
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
        alert.showAndWait();
    }

    @Override
    public boolean confirm(String title, String header, String content) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(title);
        confirm.setHeaderText(header);
        confirm.setContentText(content);
        Optional<ButtonType> result = confirm.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
    }

    @Override
    public Optional<ButtonType> choose(String title, String header, String content, ButtonType... buttons) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "", buttons);
        confirm.setTitle(title);
        confirm.setHeaderText(header);
        confirm.setContentText(content);
        return confirm.showAndWait();
    }

    @Override
    public Optional<String> askText(String title, String header, String content, String initialValue) {
        TextInputDialog dialog = new TextInputDialog(initialValue);
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        dialog.setContentText(content);
        return dialog.showAndWait();
    }

    @Override
    public boolean showForm(String title, Node form) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Optional<ButtonType> result = dialog.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
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
