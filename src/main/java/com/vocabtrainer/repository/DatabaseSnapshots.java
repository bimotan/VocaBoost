package com.vocabtrainer.repository;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Safety copies of the database ("snapshots") in a {@value #FOLDER} folder next to it, written with
 * SQLite's {@code VACUUM INTO}: a consistent copy of the whole database, also while other connections
 * write, compacted and without the write-ahead log. They are taken before a schema upgrade, before a
 * JSON backup restore and once a day at startup, and the newest {@value #KEEP} are kept; older ones
 * are deleted. Going back to one means copying it over {@code vocab.db} while the app is closed.
 *
 * <p>A snapshot is named {@code vocab-<yyyyMMdd>-<HHmmss>-<reason>.db}, so the names sort by time;
 * only files named like that are ever deleted. Taking one must not stop what it protects: the
 * {@code takeQuietly} methods log a failure and go on.
 */
public final class DatabaseSnapshots {
    /** How many snapshots are kept. */
    public static final int KEEP = 10;
    /** The folder next to the database that holds the snapshots. */
    public static final String FOLDER = "snapshots";

    private static final Logger LOGGER = Logger.getLogger(DatabaseSnapshots.class.getName());
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Pattern NAME = Pattern.compile("vocab-(\\d{8})-(\\d{6})-([a-z0-9-]+)\\.db");
    private static final Pattern REASON = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    private final DatabaseManager databaseManager;
    private final Clock clock;
    private final Path folder;
    private final int keep;

    public DatabaseSnapshots(DatabaseManager databaseManager, Clock clock) {
        this(databaseManager, clock, KEEP);
    }

    DatabaseSnapshots(DatabaseManager databaseManager, Clock clock, int keep) {
        this.databaseManager = databaseManager;
        this.clock = clock;
        this.folder = folderFor(databaseManager.getDatabasePath());
        this.keep = keep;
    }

    /** The snapshots folder of the database at {@code databasePath}: {@value #FOLDER} next to it. */
    public static Path folderFor(Path databasePath) {
        return databasePath.toAbsolutePath().resolveSibling(FOLDER);
    }

    public Path folder() {
        return folder;
    }

    /**
     * Writes a snapshot of the database now and deletes the oldest beyond {@value #KEEP}. Must not be
     * called inside a transaction (SQLite cannot vacuum there).
     *
     * @param reason ends the file name: lower-case letters, digits and hyphens, such as "daily"
     * @return the snapshot written
     */
    public Path take(String reason) throws SQLException, IOException {
        try (Connection connection = databaseManager.getConnection()) {
            return take(reason, connection);
        }
    }

    /** {@link #take(String)} on {@code connection}, which stays open; for the upgrade, which holds one. */
    Path take(String reason, Connection connection) throws SQLException, IOException {
        if (!REASON.matcher(reason).matches()) {
            throw new IllegalArgumentException("Not a snapshot reason: " + reason);
        }
        Files.createDirectories(folder);
        Path target = freeName(LocalDateTime.now(clock).format(STAMP), reason);
        try (PreparedStatement statement = connection.prepareStatement("VACUUM INTO ?")) {
            statement.setString(1, target.toString());
            statement.execute();
        } catch (SQLException e) {
            // A half-written copy is no snapshot.
            Files.deleteIfExists(target);
            throw e;
        }
        LOGGER.info("Wrote database snapshot " + target.getFileName());
        prune(target);
        return target;
    }

    /** {@link #take}, logging a failure instead of throwing it; empty when no snapshot was written. */
    public Optional<Path> takeQuietly(String reason) {
        return quietly(reason, () -> take(reason));
    }

    /** {@link #take(String, Connection)}, logging a failure instead of throwing it. */
    Optional<Path> takeQuietly(String reason, Connection connection) {
        return quietly(reason, () -> take(reason, connection));
    }

    private static Optional<Path> quietly(String reason, Snapshotter snapshotter) {
        try {
            return Optional.of(snapshotter.take());
        } catch (SQLException | IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not write a database snapshot (" + reason + ")", e);
            return Optional.empty();
        }
    }

    @FunctionalInterface
    private interface Snapshotter {
        Path take() throws SQLException, IOException;
    }

    /**
     * The daily snapshot at startup: written unless a snapshot of any kind was written today already.
     * A failure is logged; empty when none was written.
     */
    public Optional<Path> takeDailyIfDue() {
        LocalDate today = LocalDate.now(clock);
        try {
            if (list().stream().anyMatch(snapshot -> today.equals(dayOf(snapshot)))) {
                return Optional.empty();
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not list the database snapshots", e);
        }
        return takeQuietly("daily");
    }

    /**
     * The snapshots, newest first; empty when there is no snapshots folder yet. They are ordered by
     * when they were written (the file time, which a change of time zone does not move), then by name.
     */
    public List<Path> list() throws IOException {
        List<Snapshot> snapshots = new ArrayList<>();
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder)) {
            for (Path file : files) {
                if (NAME.matcher(file.getFileName().toString()).matches() && Files.isRegularFile(file)) {
                    snapshots.add(new Snapshot(file, Files.getLastModifiedTime(file).toMillis()));
                }
            }
        }
        snapshots.sort(Comparator.comparingLong(Snapshot::written)
            .thenComparing(snapshot -> snapshot.file().getFileName().toString())
            .reversed());
        return snapshots.stream().map(Snapshot::file).toList();
    }

    /**
     * Deletes the oldest snapshots so that {@code keep} are left, never {@code written}, the one just
     * taken; one that cannot be deleted is logged.
     */
    private void prune(Path written) throws IOException {
        List<Path> others = new ArrayList<>(list());
        others.remove(written);
        for (Path old : others.subList(Math.min(Math.max(0, keep - 1), others.size()), others.size())) {
            try {
                Files.deleteIfExists(old);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Could not delete the old database snapshot " + old.getFileName(), e);
            }
        }
    }

    private record Snapshot(Path file, long written) {
    }

    /** A name no file has yet; a second snapshot in the same second gets the next second's stamp. */
    private Path freeName(String stamp, String reason) {
        LocalDateTime time = LocalDateTime.parse(stamp, STAMP);
        while (true) {
            Path candidate = folder.resolve("vocab-" + time.format(STAMP) + "-" + reason + ".db");
            if (!Files.exists(candidate)) {
                return candidate;
            }
            time = time.plusSeconds(1);
        }
    }

    private static LocalDate dayOf(Path snapshot) {
        Matcher matcher = NAME.matcher(snapshot.getFileName().toString());
        return matcher.matches() ? LocalDate.parse(matcher.group(1), DateTimeFormatter.BASIC_ISO_DATE) : null;
    }
}
