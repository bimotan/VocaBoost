package com.vocabtrainer.app;

import com.vocabtrainer.util.Messages;
import javafx.application.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code --smoke-test} launch argument, which CI passes to the packaged launchers. */
@Tag("ui")
class SmokeTestUiTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final Locale JVM_DEFAULT_LOCALE = Locale.getDefault();

    @TempDir
    Path tempDir;

    private String userHome;

    @BeforeAll
    static void startJavaFx() throws InterruptedException {
        CountDownLatch running = new CountDownLatch(1);
        try {
            Platform.startup(running::countDown);
        } catch (IllegalStateException alreadyRunning) {
            Platform.runLater(running::countDown);
        }
        assertTrue(running.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), "JavaFX did not start");
        // Closing the smoke test's window must not shut the toolkit down for the next test.
        Platform.setImplicitExit(false);
    }

    @BeforeEach
    void pointUserHomeAtAnEmptyFolder() throws IOException {
        userHome = System.getProperty("user.home");
        System.setProperty("user.home", Files.createDirectory(tempDir.resolve("home")).toString());
    }

    @AfterEach
    void restore() {
        System.setProperty("user.home", userHome);
        // The app sets its language, like the main window tests leave it for the next test.
        Messages.setLocale(Messages.ENGLISH);
        Locale.setDefault(JVM_DEFAULT_LOCALE);
    }

    @Test
    void onlyTheSmokeTestArgumentStartsTheSmokeTest() {
        assertTrue(SmokeTest.requested("--smoke-test"));
        assertTrue(SmokeTest.requested("--other", "--smoke-test"));
        assertFalse(SmokeTest.requested());
        assertFalse(SmokeTest.requested("--smoke", "smoke-test"));
        assertFalse(SmokeTest.requested((String[]) null));
    }

    @Test
    void theMainWindowIsShownOnANewDatabaseInTheGivenFolderOnly() throws IOException {
        Path dataFolder = tempDir.resolve("data");

        Optional<String> failure = SmokeTest.showMainWindowOnce(dataFolder, TIMEOUT);

        assertEquals(Optional.empty(), failure);
        assertTrue(Files.isRegularFile(dataFolder.resolve("vocab.db")));
        assertEquals(List.of(), contents(tempDir.resolve("home")), "nothing is written to the home folder");
    }

    @Test
    void aStartThatFailsIsReportedWithItsCause() throws IOException {
        Path notAFolder = Files.writeString(tempDir.resolve("data"), "a file, not a folder");

        Optional<String> failure = SmokeTest.showMainWindowOnce(notAFolder, TIMEOUT);

        assertTrue(failure.orElseThrow().startsWith("the app did not start: "), failure.get());
        assertEquals(List.of(), contents(tempDir.resolve("home")));
    }

    @Test
    void theLauncherRunsTheSmokeTestOnATemporaryFolderAndExitsWithZero() throws Exception {
        Path home = tempDir.resolve("home");
        Path temp = Files.createDirectory(tempDir.resolve("tmp"));
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process process = new ProcessBuilder(java.toString(),
            "-Duser.home=" + home, "-Djava.io.tmpdir=" + temp, "-Dprism.order=sw",
            "-cp", System.getProperty("java.class.path"),
            VocabTrainerLauncher.class.getName(), SmokeTest.ARGUMENT)
            .redirectErrorStream(true)
            .redirectOutput(tempDir.resolve("output.txt").toFile())
            .start();
        boolean finished = process.waitFor(SmokeTest.TIMEOUT.toSeconds() + 30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        String output = Files.readString(tempDir.resolve("output.txt"), StandardCharsets.UTF_8);

        assertTrue(finished, output);
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("Smoke test passed: the main window was shown on a new database."), output);
        assertEquals(List.of(), contents(home), "the user's home folder is never written");
        // The temporary data folder is deleted; only JavaFX's native libraries stay, for the next run.
        assertEquals(List.of(temp.resolve("vocaboost-smoke-test-javafx")), contents(temp), output);
    }

    private static List<Path> contents(Path folder) throws IOException {
        try (Stream<Path> paths = Files.list(folder)) {
            return paths.sorted().toList();
        }
    }
}
