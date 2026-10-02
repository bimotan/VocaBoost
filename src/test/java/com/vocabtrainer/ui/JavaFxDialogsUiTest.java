package com.vocabtrainer.ui;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real form dialog: OK closes it only once the form accepts its input; it has the app's style
 * and text size, and the keyboard starts in its first field.
 */
@Tag("ui")
class JavaFxDialogsUiTest {
    private static final String TITLE = "Form under test";

    @BeforeAll
    static void startJavaFx() throws InterruptedException {
        Fx.start();
    }

    @Test
    void okClosesTheFormOnlyOnceItsInputIsAccepted() throws Exception {
        TextField field = new TextField();
        AtomicInteger checks = new AtomicInteger();
        CompletableFuture<Boolean> result = open(new JavaFxDialogs(), field, () -> {
            checks.incrementAndGet();
            return !field.getText().isBlank();
        });

        press(ButtonType.OK);
        assertEquals(1, checks.get());
        assertTrue(Fx.call(() -> dialog().isPresent()), "a rejected OK leaves the form open");
        assertFalse(result.isDone());

        Fx.run(() -> field.setText("typed"));
        press(ButtonType.OK);

        assertTrue(result.get(Fx.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
        assertEquals(2, checks.get());
        assertFalse(Fx.call(() -> dialog().isPresent()));
    }

    @Test
    void cancelClosesTheFormWithoutCheckingIt() throws Exception {
        AtomicInteger checks = new AtomicInteger();
        CompletableFuture<Boolean> result = open(new JavaFxDialogs(), new TextField(), () -> {
            checks.incrementAndGet();
            return false;
        });

        press(ButtonType.CANCEL);

        assertFalse(result.get(Fx.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
        assertEquals(0, checks.get());
    }

    @Test
    void aFormHasTheAppsStyleAndTextSizeAndTheKeyboardStartsInItsFirstField() throws Exception {
        TextField first = new TextField();
        TextField second = new TextField();
        Label problem = new Label("A problem");
        problem.getStyleClass().add("form-error");
        VBox form = new VBox(8, new Label("Name"), first, new Label("Note"), second, problem);
        CompletableFuture<Boolean> result = open(new JavaFxDialogs(() -> 130), form, () -> true);

        // The focus owner of the dialog's window; the window itself may not get the focus under xvfb.
        Fx.waitUntil("the first field has the keyboard", () -> first.getScene().getFocusOwner() == first);
        Scene scene = Fx.call(() -> dialog().orElseThrow().getScene());
        assertTrue(scene.getStylesheets().contains(AppStyle.STYLESHEET));
        assertTrue(scene.getRoot().getStyleClass().contains("text-size-130"), scene.getRoot().getStyleClass().toString());
        assertEquals(Font.getDefault().getSize() * 1.3, Fx.call(() -> problem.getFont().getSize()), 0.1);
        // The app's colours reach into the dialog: the problem is red, not the default text colour.
        assertEquals(Color.web("#b91c1c"), Fx.call(() -> problem.getTextFill()));
        press(ButtonType.OK);
        assertTrue(result.get(Fx.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
    }

    /** Shows the blocking dialog from a later FX event, so the test thread can work it meanwhile. */
    private static CompletableFuture<Boolean> open(JavaFxDialogs dialogs, Node form, BooleanSupplier onOk) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(dialogs.showForm(TITLE, form, onOk));
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        Fx.waitUntil("the form dialog is shown", () -> dialog().isPresent());
        return result;
    }

    private static void press(ButtonType buttonType) {
        Fx.run(() -> ((Button) dialog().orElseThrow().lookupButton(buttonType)).fire());
    }

    private static Optional<DialogPane> dialog() {
        return Window.getWindows().stream()
            .filter(window -> window instanceof Stage stage && TITLE.equals(stage.getTitle()) && stage.isShowing())
            .map(window -> (DialogPane) window.getScene().getRoot())
            .findFirst();
    }
}
