package com.vocabtrainer.ui;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TextField;
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

/** The real form dialog: OK closes it only once the form accepts its input. */
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
        CompletableFuture<Boolean> result = open(field, () -> {
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
        CompletableFuture<Boolean> result = open(new TextField(), () -> {
            checks.incrementAndGet();
            return false;
        });

        press(ButtonType.CANCEL);

        assertFalse(result.get(Fx.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
        assertEquals(0, checks.get());
    }

    /** Shows the blocking dialog from a later FX event, so the test thread can work it meanwhile. */
    private static CompletableFuture<Boolean> open(TextField field, BooleanSupplier onOk) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(new JavaFxDialogs().showForm(TITLE, field, onOk));
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
