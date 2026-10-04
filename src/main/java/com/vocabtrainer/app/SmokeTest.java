package com.vocabtrainer.app;

import com.vocabtrainer.ui.UiAsync;
import com.vocabtrainer.util.AppLogging;
import com.vocabtrainer.util.ErrorMessages;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.DialogPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * The {@code --smoke-test} launch argument: starts the app on a new temporary folder instead of the
 * user's data folder, waits until the main window is shown and has settled (finished its background
 * work and stayed free of errors for {@link #SETTLE}), closes it and exits with 0. A startup error, an uncaught exception, a SEVERE log record or a dialog (the app shows errors in
 * dialogs) before then, or a window that is not shown within {@link #TIMEOUT}, is printed and exits
 * with 1. CI runs the packaged launchers with it, so an app image that cannot start the app (a
 * missing Java module in the trimmed runtime, a broken launcher or JavaFX) fails the build.
 *
 * <p>The user's data is never opened: {@code user.home} points to the temporary folder before
 * anything reads it, so the database, the logs and everything else the app or a library keeps in the
 * home folder end up there. The folder is deleted after a passed test and kept after a failed one,
 * for its log. JavaFX's native libraries are unpacked to a cache folder next to it that later runs
 * reuse.
 */
public final class SmokeTest {
    public static final String ARGUMENT = "--smoke-test";
    /** How long JavaFX, the database and the main window may take to start. */
    static final Duration TIMEOUT = Duration.ofSeconds(120);
    /** How long the shown window must stay free of errors and dialogs, for work it started in the background. */
    static final Duration SETTLE = Duration.ofSeconds(2);
    /** After this, the process is stopped even if JavaFX does not shut down. */
    private static final Duration HARD_LIMIT = TIMEOUT.plusSeconds(60);
    private static final Duration POLL = Duration.ofMillis(100);

    private SmokeTest() {
    }

    public static boolean requested(String... args) {
        return args != null && Arrays.asList(args).contains(ARGUMENT);
    }

    /**
     * Runs the smoke test in this process, which is then done: it starts JavaFX and shuts it down.
     *
     * @return the exit code: 0 when the main window was shown, 1 otherwise
     */
    public static int run(PrintStream out, PrintStream err) {
        startWatchdog(err);
        Path folder;
        try {
            folder = Files.createTempDirectory("vocaboost-smoke-test-");
        } catch (IOException | RuntimeException e) {
            err.println("Smoke test failed: cannot create a temporary folder: " + ErrorMessages.causeChain(e));
            return 1;
        }
        Path tempRoot = folder.toAbsolutePath().getParent();
        System.setProperty("user.home", folder.toString());
        if (System.getProperty("javafx.cachedir") == null && tempRoot != null) {
            System.setProperty("javafx.cachedir", tempRoot.resolve("vocaboost-smoke-test-javafx").toString());
        }
        Path dataFolder = folder.resolve(".vocab-trainer");
        AppLogging.initialize(dataFolder.resolve("logs"));
        AppLogging.installUncaughtExceptionLogger();
        out.println("Smoke test: starting VocaBoost on the temporary folder " + folder);

        Optional<String> failure;
        try {
            startJavaFx();
        } catch (Throwable e) {
            // Platform.exit would wait for a toolkit that is not running.
            return failed(err, "JavaFX did not start: " + describe(e), folder);
        }
        try {
            failure = showMainWindowOnce(dataFolder, TIMEOUT);
        } finally {
            Platform.exit();
        }
        if (failure.isPresent()) {
            return failed(err, failure.get(), folder);
        }
        // The log file is open until the handlers are closed; Windows cannot delete an open file.
        LogManager.getLogManager().reset();
        deleteQuietly(folder);
        out.println("Smoke test passed: the main window was shown on a new database.");
        return 0;
    }

    private static int failed(PrintStream err, String reason, Path folder) {
        err.println("Smoke test failed: " + reason);
        err.println("The temporary folder with the log is kept: " + folder);
        return 1;
    }

    /**
     * Opens the app on a new database in {@code dataFolder} the way {@link VocabTrainerApp} starts,
     * on a new stage of the running JavaFX toolkit, waits until the main window is shown and has
     * settled for {@link #SETTLE}, and closes the window and the database again. Nothing outside
     * {@code dataFolder} is written.
     *
     * @return empty when the window was shown without errors, otherwise what went wrong
     */
    static Optional<String> showMainWindowOnce(Path dataFolder, Duration timeout) {
        List<String> problems = new CopyOnWriteArrayList<>();
        Handler severe = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.SEVERE.intValue()) {
                    problems.add("an error was logged: " + record.getMessage()
                        + (record.getThrown() == null ? "" : ": " + describe(record.getThrown())));
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger root = Logger.getLogger("");
        root.addHandler(severe);
        Thread.UncaughtExceptionHandler recording = (thread, error) -> {
            problems.add("uncaught exception in thread \"" + thread.getName() + "\": " + describe(error));
            AppLogging.logUncaught(thread, error);
        };
        Thread.UncaughtExceptionHandler previousDefault = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(recording);

        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            FxState fx;
            try {
                fx = onFxThread(() -> new FxState(new Stage(), new ArrayList<>(Window.getWindows()),
                    Thread.currentThread().getUncaughtExceptionHandler()), remaining(deadline));
            } catch (Exception e) {
                return Optional.of("the JavaFX thread did not respond: " + describe(e));
            }
            VocabTrainerApp app = new VocabTrainerApp();
            CompletableFuture<Void> opened = new CompletableFuture<>();
            try {
                Platform.runLater(() -> {
                    Thread.currentThread().setUncaughtExceptionHandler(recording);
                    try {
                        app.openMainWindow(fx.stage(), dataFolder.resolve("vocab.db"));
                        opened.complete(null);
                    } catch (Throwable e) {
                        opened.completeExceptionally(e);
                    }
                });
                return awaitShown(fx, opened, problems, timeout, deadline);
            } finally {
                close(app, fx, opened);
            }
        } finally {
            root.removeHandler(severe);
            Thread.setDefaultUncaughtExceptionHandler(previousDefault);
        }
    }

    /**
     * @param stage           the stage the main window is shown on
     * @param before          the windows open before the app started
     * @param previousHandler the JavaFX thread's uncaught exception handler before the smoke test
     */
    private record FxState(Stage stage, List<Window> before, Thread.UncaughtExceptionHandler previousHandler) {
    }

    private static Optional<String> awaitShown(FxState fx, CompletableFuture<Void> opened, List<String> problems,
                                               Duration timeout, long deadline) {
        Long shownAt = null;
        while (true) {
            // Asked on the JavaFX thread after the start, so a start that has run is done by then.
            Status status;
            try {
                status = onFxThread(() -> new Status(fx.stage().isShowing(), newDialog(fx)), remaining(deadline));
            } catch (TimeoutException e) {
                return Optional.of("the main window was not shown within " + timeout.toSeconds()
                    + " seconds (the JavaFX thread is busy)");
            } catch (Exception e) {
                return Optional.of("the JavaFX thread failed: " + describe(e));
            }
            if (opened.isCompletedExceptionally()) {
                return Optional.of("the app did not start: " + describe(cause(opened)));
            }
            if (!problems.isEmpty()) {
                return Optional.of(problems.get(0));
            }
            if (status.dialog().isPresent()) {
                return Optional.of("a dialog opened: " + status.dialog().get());
            }
            long now = System.nanoTime();
            if (opened.isDone() && status.showing()) {
                if (shownAt == null) {
                    shownAt = now;
                } else if (now - shownAt >= SETTLE.toNanos() && !backgroundTasksRunning()) {
                    // The results of finished background tasks were applied before this status was read.
                    return Optional.empty();
                }
            } else if (opened.isDone()) {
                return Optional.of("the app started but did not show the main window");
            }
            if (now - deadline >= 0) {
                return Optional.of("the main window was not shown within " + timeout.toSeconds() + " seconds");
            }
            sleep(POLL);
        }
    }

    /** Whether work the window started in the background ({@link UiAsync}) is still running. */
    private static boolean backgroundTasksRunning() {
        return Thread.getAllStackTraces().keySet().stream()
            .anyMatch(thread -> thread.isAlive() && UiAsync.THREAD_NAME.equals(thread.getName()));
    }

    /** Whether the main window is showing, and the title and text of a dialog the app opened. */
    private record Status(boolean showing, Optional<String> dialog) {
    }

    /** A window that was not open before the app started, other than the main window: a dialog. */
    private static Optional<String> newDialog(FxState fx) {
        return Window.getWindows().stream()
            .filter(window -> window instanceof Stage && window != fx.stage() && window.isShowing()
                && !fx.before().contains(window))
            .map(window -> describe((Stage) window))
            .findFirst();
    }

    private static String describe(Stage dialog) {
        StringBuilder text = new StringBuilder(String.valueOf(dialog.getTitle()));
        Scene scene = dialog.getScene();
        if (scene != null && scene.getRoot() instanceof DialogPane pane) {
            Stream.of(pane.getHeaderText(), pane.getContentText())
                .filter(part -> part != null && !part.isBlank())
                .forEach(part -> text.append(" - ").append(part.strip()));
        }
        return text.toString();
    }

    /** Hides the window and every dialog the app opened, then closes the database. */
    private static void close(VocabTrainerApp app, FxState fx, CompletableFuture<Void> opened) {
        Runnable hide = () -> new ArrayList<>(Window.getWindows()).stream()
            .filter(window -> window == fx.stage() || !fx.before().contains(window))
            .forEach(Window::hide);
        try {
            // A dialog open while the window is built holds the start up; hiding it lets the start finish.
            onFxThread(() -> {
                hide.run();
                return null;
            }, Duration.ofSeconds(10));
            opened.get(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // The start failed or is stuck; what it opened is closed below as far as possible.
        }
        // The database is closed after the work that uses it.
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (backgroundTasksRunning() && System.nanoTime() - deadline < 0) {
            sleep(POLL);
        }
        try {
            onFxThread(() -> {
                hide.run();
                app.stop();
                Thread.currentThread().setUncaughtExceptionHandler(fx.previousHandler());
                return null;
            }, Duration.ofSeconds(10));
        } catch (Exception e) {
            Logger.getLogger(SmokeTest.class.getName()).log(Level.WARNING, "Cannot close the smoke test's window", e);
        }
    }

    private static void startJavaFx() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(started::countDown);
        Platform.setImplicitExit(false);
        if (!started.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("the toolkit did not start within " + TIMEOUT.toSeconds() + " seconds");
        }
    }

    /** Stops the process if the smoke test or JavaFX's shutdown hangs, so CI never waits for its own timeout. */
    private static void startWatchdog(PrintStream err) {
        Thread watchdog = new Thread(() -> {
            sleep(HARD_LIMIT);
            err.println("Smoke test failed: still running after " + HARD_LIMIT.toSeconds() + " seconds.");
            err.flush();
            Runtime.getRuntime().halt(1);
        }, "vocaboost-smoke-test-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private static <T> T onFxThread(Callable<T> action, Duration timeout) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(action.call());
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });
        try {
            return result.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        }
    }

    private static Duration remaining(long deadline) {
        return Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
    }

    private static Throwable cause(CompletableFuture<?> future) {
        try {
            future.join();
            return new IllegalStateException("no error");
        } catch (RuntimeException e) {
            return e.getCause() != null ? e.getCause() : e;
        }
    }

    private static String describe(Throwable error) {
        return ErrorMessages.causeChain(error) + System.lineSeparator() + ErrorMessages.stackTrace(error);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void deleteQuietly(Path folder) {
        try (Stream<Path> paths = Files.walk(folder)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A temporary folder; the system cleans it up eventually.
                }
            });
        } catch (IOException | RuntimeException ignored) {
            // As above.
        }
    }
}
