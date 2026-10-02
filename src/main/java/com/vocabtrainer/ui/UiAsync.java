package com.vocabtrainer.ui;

import javafx.concurrent.Task;
import javafx.scene.Node;
import javafx.scene.control.Labeled;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs slow work on a background thread and hands the result back on the JavaFX thread.
 *
 * <p>The callbacks run when the work finishes, which can be after the user switched deck or moved
 * to the next card. Callers therefore capture everything the work is for (deck, card, request
 * ticket) in local variables before starting it and use only those in the callbacks, never the
 * window's current state. Buttons passed as {@code triggers} are disabled while the work runs, so
 * the same action cannot run twice at once.
 */
public final class UiAsync implements TaskRunner {
    /** The name of every background thread; the UI tests wait for these threads to finish. */
    public static final String THREAD_NAME = "vocaboost-background-task";

    private static final Logger LOGGER = Logger.getLogger(UiAsync.class.getName());

    private final UiErrors errors;

    public UiAsync(UiErrors errors) {
        this.errors = errors;
    }

    @Override
    public <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
        run(work, onSuccess, onFailure, null, null);
    }

    /**
     * Like {@link #run(Callable, Consumer, Consumer)}, and shows {@code runningMessage} in
     * {@code status} while the work runs. When the work is done the previous status comes back
     * unless a callback set a new one; a failure the callback does not explain shows "Failed: ...".
     */
    public <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure,
                        Labeled status, String runningMessage, Node... triggers) {
        boolean showsProgress = status != null && runningMessage != null;
        String previousStatus = showsProgress ? status.getText() : null;
        if (showsProgress) {
            status.setText(runningMessage);
        }
        List<Node> disabled = new ArrayList<>();
        for (Node trigger : triggers) {
            if (!trigger.isDisable()) {
                trigger.setDisable(true);
                disabled.add(trigger);
            }
        }
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return work.call();
            }
        };
        task.setOnSucceeded(event -> {
            disabled.forEach(trigger -> trigger.setDisable(false));
            try {
                onSuccess.accept(task.getValue());
                if (showsProgress && runningMessage.equals(status.getText())) {
                    status.setText(previousStatus);
                }
            } catch (RuntimeException e) {
                // A failing success callback is a failure too, not something to lose on the FX thread.
                handleFailure(e, onFailure, status, runningMessage);
            }
        });
        task.setOnFailed(event -> {
            disabled.forEach(trigger -> trigger.setDisable(false));
            handleFailure(task.getException(), onFailure, status, runningMessage);
        });
        Thread thread = new Thread(task, THREAD_NAME);
        thread.setDaemon(true);
        thread.start();
    }

    private void handleFailure(Throwable error, Consumer<Throwable> onFailure, Labeled status, String runningMessage) {
        LOGGER.log(Level.WARNING, "Background task failed" + (runningMessage == null ? "" : ": " + runningMessage), error);
        if (status != null && runningMessage != null && runningMessage.equals(status.getText())) {
            status.setText("Failed: " + UiErrors.rootMessage(error));
        }
        errors.guard("Unexpected error", () -> onFailure.accept(error));
    }
}
