package com.vocabtrainer.ui;

import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * {@link Dialogs} for UI tests. Nothing is shown: every dialog is recorded, and prompts are answered
 * from a script the test sets up beforehand. A prompt without a scripted answer fails the test
 * instead of blocking it, and error alerts must be claimed with {@link #takeError()}.
 */
final class ScriptedDialogs implements Dialogs {
    enum Kind { ERROR, INFO, TEXT, CONFIRM, CHOOSE, ASK_TEXT, FORM, OPEN_FILE, SAVE_FILE }

    /**
     * One dialog the window opened. {@code value} is the prompt's initial text, the save chooser's
     * initial file name or the choice's button labels.
     */
    record Shown(Kind kind, String title, String header, String content, String value) {
    }

    private record Answer(Kind kind, Object value) {
    }

    private final List<Shown> shown = new CopyOnWriteArrayList<>();
    private final List<Shown> unclaimedErrors = new CopyOnWriteArrayList<>();
    private final Deque<Answer> script = new ConcurrentLinkedDeque<>();

    ScriptedDialogs answerText(String text) {
        return expect(Kind.ASK_TEXT, Optional.of(text));
    }

    ScriptedDialogs cancelText() {
        return expect(Kind.ASK_TEXT, Optional.empty());
    }

    ScriptedDialogs confirm(boolean ok) {
        return expect(Kind.CONFIRM, ok);
    }

    ScriptedDialogs chooseButton(String buttonText) {
        return expect(Kind.CHOOSE, buttonText);
    }

    ScriptedDialogs submitForm(Consumer<Node> fill) {
        return expect(Kind.FORM, Optional.of(fill));
    }

    ScriptedDialogs cancelForm() {
        return expect(Kind.FORM, Optional.empty());
    }

    ScriptedDialogs openFile(Path file) {
        return expect(Kind.OPEN_FILE, Optional.of(file));
    }

    ScriptedDialogs cancelOpenFile() {
        return expect(Kind.OPEN_FILE, Optional.empty());
    }

    ScriptedDialogs saveFile(Path file) {
        return expect(Kind.SAVE_FILE, Optional.of(file));
    }

    private ScriptedDialogs expect(Kind kind, Object value) {
        script.addLast(new Answer(kind, value));
        return this;
    }

    List<Shown> shown() {
        return List.copyOf(shown);
    }

    List<Shown> shown(Kind kind) {
        return shown.stream().filter(dialog -> dialog.kind() == kind).toList();
    }

    Shown last(Kind kind) {
        List<Shown> matching = shown(kind);
        if (matching.isEmpty()) {
            throw new AssertionError("No " + kind + " dialog was shown; shown: " + shown);
        }
        return matching.get(matching.size() - 1);
    }

    boolean wasShown(Kind kind) {
        return !shown(kind).isEmpty();
    }

    /** Claims the one error alert shown since the last claim. */
    Shown takeError() {
        if (unclaimedErrors.size() != 1) {
            throw new AssertionError("Expected exactly one error dialog but got " + unclaimedErrors);
        }
        return unclaimedErrors.remove(0);
    }

    /** Fails if an error alert was not claimed or a scripted answer was not used. */
    void verifyFinished() {
        if (!unclaimedErrors.isEmpty()) {
            throw new AssertionError("Unexpected error dialog(s): " + unclaimedErrors);
        }
        if (!script.isEmpty()) {
            throw new AssertionError("Scripted dialog answer(s) never used: " + script);
        }
    }

    @Override
    public void showError(String title, String message) {
        Shown dialog = new Shown(Kind.ERROR, title, title, message, null);
        shown.add(dialog);
        unclaimedErrors.add(dialog);
    }

    @Override
    public void showInfo(String message) {
        shown.add(new Shown(Kind.INFO, "Info", null, message, null));
    }

    @Override
    public void showText(String title, String header, String text, int rows) {
        shown.add(new Shown(Kind.TEXT, title, header, text, null));
    }

    @Override
    public boolean confirm(String title, String header, String content) {
        return (Boolean) answer(new Shown(Kind.CONFIRM, title, header, content, null));
    }

    @Override
    public Optional<ButtonType> choose(String title, String header, String content, ButtonType... buttons) {
        String labels = Arrays.stream(buttons).map(ButtonType::getText).collect(Collectors.joining(" | "));
        Shown dialog = new Shown(Kind.CHOOSE, title, header, content, labels);
        String chosen = (String) answer(dialog);
        ButtonType button = Arrays.stream(buttons)
            .filter(candidate -> candidate.getText().equals(chosen))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No button '" + chosen + "' in " + dialog));
        return Optional.of(button);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<String> askText(String title, String header, String content, String initialValue) {
        return (Optional<String>) answer(new Shown(Kind.ASK_TEXT, title, header, content, initialValue));
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean showForm(String title, Node form) {
        Optional<Consumer<Node>> fill = (Optional<Consumer<Node>>) answer(new Shown(Kind.FORM, title, null, null, null));
        fill.ifPresent(edit -> edit.accept(form));
        return fill.isPresent();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<Path> chooseOpenFile(Window owner, String title, List<FileChooser.ExtensionFilter> filters) {
        return (Optional<Path>) answer(new Shown(Kind.OPEN_FILE, title, null, describe(filters), null));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<Path> chooseSaveFile(Window owner, String title, String initialFileName,
                                         List<FileChooser.ExtensionFilter> filters) {
        return (Optional<Path>) answer(new Shown(Kind.SAVE_FILE, title, null, describe(filters), initialFileName));
    }

    private Object answer(Shown dialog) {
        shown.add(dialog);
        Answer answer = script.pollFirst();
        if (answer == null) {
            throw new AssertionError("The window opened a dialog the test did not script: " + dialog);
        }
        if (answer.kind() != dialog.kind()) {
            throw new AssertionError("The test scripted a " + answer.kind() + " answer, but the window opened " + dialog);
        }
        return answer.value();
    }

    private static String describe(List<FileChooser.ExtensionFilter> filters) {
        return filters.stream()
            .map(filter -> filter.getDescription() + " " + filter.getExtensions())
            .collect(Collectors.joining(", "));
    }
}
