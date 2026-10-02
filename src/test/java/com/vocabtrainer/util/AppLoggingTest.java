package com.vocabtrainer.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppLoggingTest {
    @TempDir
    Path tempDir;

    // A private logger so the tests never touch the JVM-wide root logger.
    private final Logger logger = Logger.getLogger("com.vocabtrainer.test.AppLoggingTest." + System.nanoTime());

    @AfterEach
    void closeHandlers() {
        for (Handler handler : logger.getHandlers()) {
            logger.removeHandler(handler);
            handler.close();
        }
    }

    @Test
    void writesReadableUtf8LogFileIntoGivenDirectory() throws Exception {
        logger.setUseParentHandlers(false);
        Path logDirectory = tempDir.resolve("logs");

        Optional<Path> active = AppLogging.configure(logger, logDirectory);
        silenceConsole();
        logger.log(Level.WARNING, "词库缓存不可用", new IllegalStateException("cache failed",
            new SQLException("[SQLITE_BUSY] database is locked")));
        Arrays.stream(logger.getHandlers()).forEach(Handler::flush);

        assertEquals(Optional.of(logDirectory.toAbsolutePath()), active);
        Path logFile = logDirectory.resolve(AppLogging.CURRENT_LOG_FILE);
        assertTrue(Files.isRegularFile(logFile));
        String content = Files.readString(logFile, StandardCharsets.UTF_8);
        String firstLine = content.lines().findFirst().orElse("");
        assertTrue(firstLine.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} WARNING "
            + logger.getName().replace(".", "\\.") + " - 词库缓存不可用"), firstLine);
        assertTrue(content.contains("java.lang.IllegalStateException: cache failed"));
        assertTrue(content.contains("Caused by: java.sql.SQLException: [SQLITE_BUSY] database is locked"));
        assertTrue(Arrays.stream(logger.getHandlers()).anyMatch(handler -> handler instanceof ConsoleHandler));
    }

    @Test
    void fallsBackToConsoleWhenLogDirectoryCannotBeCreated() throws Exception {
        logger.setUseParentHandlers(false);
        Path regularFile = Files.writeString(tempDir.resolve("not-a-directory"), "x");
        List<LogRecord> records = new ArrayList<>();
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });

        // The console handler is created and used inside configure(); keep its warning off the build output.
        PrintStream originalErr = System.err;
        Optional<Path> active;
        try {
            System.setErr(new PrintStream(OutputStream.nullOutputStream()));
            active = AppLogging.configure(logger, regularFile.resolve("logs"));
        } finally {
            System.setErr(originalErr);
        }
        logger.info("still logging");

        assertTrue(active.isEmpty());
        assertEquals(Level.WARNING, records.get(0).getLevel());
        assertTrue(records.get(0).getMessage().startsWith("File logging disabled"));
        assertTrue(records.get(0).getThrown() instanceof IOException);
        assertEquals("still logging", records.get(1).getMessage());
        Handler[] handlers = logger.getHandlers();
        assertTrue(Arrays.stream(handlers).anyMatch(handler -> handler instanceof ConsoleHandler));
        assertFalse(Arrays.stream(handlers).anyMatch(handler -> handler instanceof FileHandler));
    }

    @Test
    void replacesExistingConsoleHandlerInsteadOfDuplicatingIt() {
        logger.setUseParentHandlers(false);
        logger.addHandler(new ConsoleHandler());

        AppLogging.configure(logger, tempDir.resolve("logs"));

        long consoleHandlers = Arrays.stream(logger.getHandlers())
            .filter(handler -> handler instanceof ConsoleHandler)
            .count();
        assertEquals(1, consoleHandlers);
    }

    @Test
    void defaultLogDirectoryIsNextToTheDatabase() {
        assertEquals(DateTimeUtil.defaultDatabasePath().toAbsolutePath().getParent().resolve("logs"),
            AppLogging.defaultLogDirectory());
    }

    private void silenceConsole() {
        Arrays.stream(logger.getHandlers())
            .filter(handler -> handler instanceof ConsoleHandler)
            .forEach(handler -> handler.setLevel(Level.OFF));
    }
}
