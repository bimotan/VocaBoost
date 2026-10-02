package com.vocabtrainer.util;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Configures java.util.logging once at startup: a rotating UTF-8 log file in the data folder
 * (~/.vocab-trainer/logs) plus console output. Logging problems never stop the app; when the
 * log folder cannot be used it falls back to console output only.
 */
public final class AppLogging {
    public static final String LOG_FILE_PATTERN = "vocaboost-%g.log";
    public static final String CURRENT_LOG_FILE = "vocaboost-0.log";
    static final int LIMIT_BYTES = 1024 * 1024;
    static final int FILE_COUNT = 5;

    private static final Logger LOGGER = Logger.getLogger(AppLogging.class.getName());
    private static boolean initialized;
    private static volatile Path activeLogDirectory;

    private AppLogging() {
    }

    public static Path defaultLogDirectory() {
        return DateTimeUtil.defaultDatabasePath().toAbsolutePath().resolveSibling("logs");
    }

    public static void initialize() {
        initialize(defaultLogDirectory());
    }

    /** Configures the root logger the first time it is called; later calls do nothing. */
    public static synchronized void initialize(Path logDirectory) {
        if (initialized) {
            return;
        }
        initialized = true;
        try {
            activeLogDirectory = configure(Logger.getLogger(""), logDirectory).orElse(null);
            LOGGER.info("VocaBoost starting (Java " + System.getProperty("java.version")
                + ", " + System.getProperty("os.name") + "); log folder: "
                + (activeLogDirectory == null ? "unavailable" : activeLogDirectory));
        } catch (RuntimeException e) {
            // Logging is a diagnostic aid; it must never prevent startup.
            activeLogDirectory = null;
            e.printStackTrace();
        }
    }

    /** The folder the log file is written to, or empty when only console logging is active. */
    public static Optional<Path> logDirectory() {
        return Optional.ofNullable(activeLogDirectory);
    }

    /** A sentence for error dialogs telling the user where the details can be found. */
    public static String logLocationText() {
        return logDirectory()
            .map(directory -> "Details were written to the log folder: " + directory)
            .orElse("File logging is unavailable; details were written to the console only.");
    }

    /** Logs exceptions that nothing else caught, e.g. a failure while JavaFX is still starting. */
    public static void installUncaughtExceptionLogger() {
        Thread.setDefaultUncaughtExceptionHandler(AppLogging::logUncaught);
    }

    public static void logUncaught(Thread thread, Throwable error) {
        String threadName = thread == null ? "unknown" : thread.getName();
        LOGGER.log(Level.SEVERE, "Uncaught exception in thread \"" + threadName + "\"", error);
    }

    /**
     * Replaces the console handlers of {@code logger} with one using the app's line format and
     * adds a rotating file handler in {@code logDirectory}.
     *
     * @return the directory the log file is written to, or empty when file logging is unavailable
     */
    static Optional<Path> configure(Logger logger, Path logDirectory) {
        LineFormatter formatter = new LineFormatter();
        for (Handler handler : logger.getHandlers()) {
            if (handler instanceof ConsoleHandler) {
                logger.removeHandler(handler);
                handler.close();
            }
        }
        ConsoleHandler console = new ConsoleHandler();
        console.setFormatter(formatter);
        logger.addHandler(console);

        try {
            Path directory = logDirectory.toAbsolutePath();
            Files.createDirectories(directory);
            // FileHandler treats '%' as a placeholder, so escape it in the directory part.
            String pattern = directory.toString().replace("%", "%%") + "/" + LOG_FILE_PATTERN;
            FileHandler file = new FileHandler(pattern, LIMIT_BYTES, FILE_COUNT, true);
            file.setEncoding(StandardCharsets.UTF_8.name());
            file.setFormatter(formatter);
            file.setLevel(Level.ALL);
            logger.addHandler(file);
            return Optional.of(directory);
        } catch (Exception e) {
            LogRecord record = new LogRecord(Level.WARNING,
                "File logging disabled: cannot write logs to " + logDirectory + "; using console output only.");
            record.setLoggerName(AppLogging.class.getName());
            record.setThrown(e);
            logger.log(record);
            return Optional.empty();
        }
    }

    /** One line per record (timestamp, level, logger, message) followed by any stack trace. */
    static final class LineFormatter extends Formatter {
        private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

        @Override
        public String format(LogRecord record) {
            String loggerName = record.getLoggerName() == null || record.getLoggerName().isEmpty()
                ? "root"
                : record.getLoggerName();
            StringBuilder builder = new StringBuilder()
                .append(TIMESTAMP.format(LocalDateTime.ofInstant(record.getInstant(), ZoneId.systemDefault())))
                .append(' ').append(record.getLevel().getName())
                .append(' ').append(loggerName)
                .append(" - ").append(formatMessage(record))
                .append(System.lineSeparator());
            if (record.getThrown() != null) {
                builder.append(ErrorMessages.stackTrace(record.getThrown()));
            }
            return builder.toString();
        }
    }
}
