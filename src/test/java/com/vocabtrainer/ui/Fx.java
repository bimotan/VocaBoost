package com.vocabtrainer.ui;

import javafx.application.Platform;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs UI-test code on the JavaFX Application Thread. The toolkit is started once per JVM, and a
 * failure on the FX thread is rethrown in the test instead of being printed and lost.
 */
final class Fx {
    static final Duration TIMEOUT = Duration.ofSeconds(20);

    private static final List<Throwable> UNCAUGHT = new CopyOnWriteArrayList<>();
    private static boolean started;

    private Fx() {
    }

    /** Starts the JavaFX toolkit unless an earlier test class in this JVM already did. */
    static synchronized void start() throws InterruptedException {
        if (started) {
            return;
        }
        CountDownLatch running = new CountDownLatch(1);
        try {
            Platform.startup(running::countDown);
        } catch (IllegalStateException alreadyRunning) {
            Platform.runLater(running::countDown);
        }
        if (!running.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new AssertionError("The JavaFX toolkit did not start within " + TIMEOUT);
        }
        // Closing a test's window must not shut the toolkit down for the next test.
        Platform.setImplicitExit(false);
        // Exceptions from event handlers and runLater blocks the tests did not run themselves.
        run(() -> Thread.currentThread().setUncaughtExceptionHandler((thread, error) -> UNCAUGHT.add(error)));
        started = true;
    }

    static void run(Runnable action) {
        call(() -> {
            action.run();
            return null;
        });
    }

    /** Runs {@code action} on the FX thread, waits for it and rethrows whatever it threw. */
    static <T> T call(Callable<T> action) {
        if (Platform.isFxApplicationThread()) {
            try {
                return action.call();
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(action.call());
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        try {
            return result.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        } catch (TimeoutException e) {
            throw new AssertionError("The JavaFX thread did not finish within " + TIMEOUT
                + "; is it blocked by a dialog that bypasses Dialogs?", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for the JavaFX thread", e);
        }
    }

    /** Polls {@code condition} on the FX thread until it holds, e.g. until a background task has updated a label. */
    static void waitUntil(String description, Callable<Boolean> condition) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (!Boolean.TRUE.equals(call(condition))) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out after " + TIMEOUT + " waiting until " + description);
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting until " + description, e);
            }
        }
    }

    /** Runs everything already queued on the FX thread. */
    static void flush() {
        run(() -> {
        });
    }

    /** The exceptions that reached the FX thread's uncaught-exception handler since the last call. */
    static List<Throwable> drainUncaught() {
        List<Throwable> drained = new ArrayList<>(UNCAUGHT);
        UNCAUGHT.removeAll(drained);
        return drained;
    }
}
