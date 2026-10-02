package com.vocabtrainer.repository;

import com.vocabtrainer.domain.EcdictMetadata;
import com.vocabtrainer.domain.EcdictRow;
import com.vocabtrainer.util.DateTimeUtil;
import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The imported ECDICT dictionary. It lives in its own SQLite file next to vocab.db
 * ({@code ecdict.db}), so the learner's database and its backups do not grow by the dictionary's
 * 100+ MB, and replacing or deleting it never touches learning data.
 *
 * <p>Lookups read the file through one read-only connection; they are short indexed queries, so
 * they take turns. An import never writes to that file: {@link #beginImport()} builds a complete
 * new file next to it ({@code ecdict.db.importing}), and {@link EcdictImport#commit} swaps it in
 * only when every row is written. Lookups keep answering from the old dictionary meanwhile, and a
 * canceled or failed import, or a crash, leaves the old dictionary as it was.
 *
 * <p>The app can run twice on the same files. An import holds a lock on {@code ecdict.db.import-lock}
 * while it builds its file, so only one window imports at a time and a window that starts never
 * deletes the file another one is still building.
 */
public class EcdictRepository implements AutoCloseable {
    /** Stored as {@code PRAGMA user_version}; a file of another version reads as not imported. */
    static final int FORMAT_VERSION = 1;

    private static final Logger LOGGER = Logger.getLogger(EcdictRepository.class.getName());
    private static final int BATCH_SIZE = 5_000;
    private static final int MAX_BASE_FORMS = 5;
    private static final String COLUMNS =
        "word, phonetic, definition, translation, pos, collins, oxford, tag, bnc, frq, exchange, example";
    /** Uses the primary key's index; {@code word} compares ignoring case. */
    static final String FIND_SQL = "SELECT " + COLUMNS + " FROM ecdict WHERE word = ?";
    /** Uses the primary keys of both tables. */
    static final String FIND_BASE_FORMS_SQL = "SELECT f.kinds, e." + COLUMNS.replace(", ", ", e.") + """
         FROM ecdict_forms f
        JOIN ecdict e ON e.word = f.lemma
        WHERE f.form = ?
        ORDER BY CASE WHEN e.frq > 0 THEN e.frq ELSE 1000000000 END, e.word
        LIMIT ?""";
    /** ECDICT's ranks (1 is the most common word) are 0 or empty when unknown. */
    private static final String BY_FREQUENCY =
        "CASE WHEN frq > 0 THEN frq WHEN bnc > 0 THEN bnc ELSE 2147483647 END";

    private final Path databasePath;
    private final Path importPath;
    private final Path importLockPath;
    private final Object lock = new Object();
    private final AtomicBoolean importing = new AtomicBoolean();
    /** Guarded by lock. */
    private Connection reader;
    /** Guarded by lock. */
    private boolean closed;

    /** Deletes what an import left behind when the app was closed or crashed during it. */
    public EcdictRepository(Path databasePath) {
        this.databasePath = databasePath.toAbsolutePath();
        this.importPath = this.databasePath.resolveSibling(this.databasePath.getFileName() + ".importing");
        this.importLockPath = this.databasePath.resolveSibling(this.databasePath.getFileName() + ".import-lock");
        deleteLeftoverImport();
    }

    public Path databasePath() {
        return databasePath;
    }

    /** What the dictionary was imported from; empty when nothing is imported. */
    public Optional<EcdictMetadata> metadata() throws SQLException {
        return read(connection -> {
            Map<String, String> values = new HashMap<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT key, value FROM ecdict_meta")) {
                while (rs.next()) {
                    values.put(rs.getString("key"), rs.getString("value"));
                }
            }
            if (!values.containsKey("source_path")) {
                return Optional.empty();
            }
            return Optional.of(new EcdictMetadata(
                values.get("source_path"),
                parseLong(values.get("source_size")),
                parseLong(values.get("source_modified_millis")),
                (int) parseLong(values.get("row_count")),
                (int) parseLong(values.get("skipped_rows")),
                DateTimeUtil.fromDatabase(values.get("imported_at")),
                values.getOrDefault("format", ""),
                parseLong(values.get("duration_millis"))
            ));
        }, Optional.empty());
    }

    /** The entry for {@code word}, ignoring case; empty when it is not in the dictionary or nothing is imported. */
    public Optional<EcdictRow> find(String word) throws SQLException {
        return read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(FIND_SQL)) {
                statement.setString(1, word);
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
                }
            }
        }, Optional.empty());
    }

    /**
     * The entries whose inflections include {@code form}, such as "abandon" for "abandoned", most
     * frequent first; see {@link EcdictImport#addForm}.
     */
    public List<BaseForm> findBaseForms(String form) throws SQLException {
        return read(connection -> {
            List<BaseForm> forms = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(FIND_BASE_FORMS_SQL)) {
                statement.setString(1, form);
                statement.setInt(2, MAX_BASE_FORMS);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        forms.add(new BaseForm(mapRow(rs), rs.getString("kinds")));
                    }
                }
            }
            return forms;
        }, List.of());
    }

    /**
     * The entries whose space-separated {@code tag} field names {@code tag}, such as "gre" or "cet4",
     * in {@code order}. ECDICT has no index on its tags, so this reads the whole table once.
     *
     * @throws IllegalArgumentException if {@code tag} is not 1 to 16 lower-case letters and digits
     */
    public List<EcdictRow> findByTag(String tag, TagOrder order) throws SQLException {
        if (tag == null || !tag.matches("[a-z0-9]{1,16}")) {
            throw new IllegalArgumentException("Not an ECDICT tag: " + tag);
        }
        String sql = "SELECT " + COLUMNS + " FROM ecdict WHERE (' ' || tag || ' ') LIKE ? ORDER BY "
            + (order == TagOrder.ALPHABETICAL ? "word, rowid" : BY_FREQUENCY + ", word, rowid");
        return read(connection -> {
            List<EcdictRow> rows = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, "% " + tag + " %");
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        rows.add(mapRow(rs));
                    }
                }
            }
            return rows;
        }, List.of());
    }

    /** How {@link #findByTag} lists the entries of a tag. */
    public enum TagOrder {
        /** Most common first: by the {@code frq} rank, else the {@code bnc} rank; words without either last. */
        FREQUENCY,
        /** By word, ignoring case. */
        ALPHABETICAL
    }

    /**
     * Starts building a new dictionary file. Only one import runs at a time; close the returned
     * import (try-with-resources) so an unfinished one is discarded.
     *
     * @throws IllegalStateException when another import is running, in this window or another one
     */
    public EcdictImport beginImport() throws SQLException, IOException {
        if (!importing.compareAndSet(false, true)) {
            throw new IllegalStateException("An ECDICT import is already running.");
        }
        ImportLock lock;
        try {
            lock = ImportLock.tryAcquire(importLockPath);
        } catch (IOException | RuntimeException e) {
            importing.set(false);
            throw e;
        }
        if (lock == null) {
            importing.set(false);
            throw new IllegalStateException(
                "Another VocaBoost window is importing ECDICT. Try again when it has finished.");
        }
        try {
            // Left behind by a crash or by an import the app was closed during.
            Files.deleteIfExists(importPath);
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + importPath);
            try {
                try (Statement statement = connection.createStatement()) {
                    // A scratch file that is deleted if anything goes wrong needs no journal and no fsync.
                    statement.execute("PRAGMA journal_mode = OFF");
                    statement.execute("PRAGMA synchronous = OFF");
                    statement.execute("PRAGMA locking_mode = EXCLUSIVE");
                    statement.execute("PRAGMA cache_size = -65536");
                    statement.execute("""
                        CREATE TABLE ecdict (
                            word TEXT NOT NULL PRIMARY KEY COLLATE NOCASE,
                            phonetic TEXT,
                            definition TEXT,
                            translation TEXT NOT NULL,
                            pos TEXT,
                            collins INTEGER,
                            oxford INTEGER,
                            tag TEXT,
                            bnc INTEGER,
                            frq INTEGER,
                            exchange TEXT,
                            example TEXT
                        )
                        """);
                    statement.execute("""
                        CREATE TABLE ecdict_forms (
                            form TEXT NOT NULL COLLATE NOCASE,
                            lemma TEXT NOT NULL COLLATE NOCASE,
                            kinds TEXT NOT NULL,
                            PRIMARY KEY (form, lemma)
                        ) WITHOUT ROWID
                        """);
                    statement.execute("CREATE TABLE ecdict_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
                    statement.execute("BEGIN");
                }
                return new EcdictImport(connection, lock);
            } catch (SQLException | RuntimeException e) {
                closeQuietly(connection);
                throw e;
            }
        } catch (SQLException | IOException | RuntimeException e) {
            deleteImportFileQuietly();
            lock.close();
            importing.set(false);
            throw e;
        }
    }

    /** True while an import is being built. */
    public boolean isImporting() {
        return importing.get();
    }

    /**
     * Deletes the imported dictionary, for example when the user clears the ECDICT path.
     *
     * @throws IllegalStateException while an import is running
     */
    public void delete() throws IOException {
        if (importing.get()) {
            throw new IllegalStateException("Cancel the running ECDICT import first.");
        }
        synchronized (lock) {
            closeReader();
            Files.deleteIfExists(databasePath);
            Files.deleteIfExists(journalPath());
        }
    }

    /** Closes the read connection; lookups fail afterwards and a running import is discarded when it ends. */
    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            closeReader();
        }
    }

    private <T> T read(SqlWork<T> query, T whenAbsent) throws SQLException {
        synchronized (lock) {
            if (closed) {
                throw new SQLException("The ECDICT dictionary is closed: " + databasePath);
            }
            if (reader == null) {
                if (!Files.isRegularFile(databasePath)) {
                    return whenAbsent;
                }
                Connection connection = openReader();
                if (userVersion(connection) != FORMAT_VERSION) {
                    // Written by another version of the app (or not by this app at all): import again.
                    closeQuietly(connection);
                    return whenAbsent;
                }
                reader = connection;
            }
            return query.run(reader);
        }
    }

    private Connection openReader() throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        return DriverManager.getConnection("jdbc:sqlite:" + databasePath, config.toProperties());
    }

    private static int userVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            closeQuietly(connection);
            throw e;
        }
    }

    /** Guarded by lock. */
    private void closeReader() {
        if (reader != null) {
            closeQuietly(reader);
            reader = null;
        }
    }

    private Path journalPath() {
        return databasePath.resolveSibling(databasePath.getFileName() + "-journal");
    }

    /** Puts the finished import file in place of the dictionary file. */
    private void replaceDatabase() throws IOException {
        synchronized (lock) {
            if (closed) {
                throw new IOException("The app is closing; the ECDICT import was discarded.");
            }
            closeReader();
            // Only a crashed writer leaves a journal, and it must not be applied to the new file.
            Files.deleteIfExists(journalPath());
            try {
                Files.move(importPath, databasePath, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(importPath, databasePath, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** Deletes the file of an unfinished import, unless another window is still building it. */
    private void deleteLeftoverImport() {
        if (!Files.exists(importPath)) {
            return;
        }
        try (ImportLock lock = ImportLock.tryAcquire(importLockPath)) {
            if (lock != null) {
                Files.deleteIfExists(importPath);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Cannot delete the unfinished ECDICT import " + importPath, e);
        }
    }

    private void deleteImportFileQuietly() {
        try {
            Files.deleteIfExists(importPath);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Cannot delete the unfinished ECDICT import " + importPath, e);
        }
    }

    private static EcdictRow mapRow(ResultSet rs) throws SQLException {
        return new EcdictRow(
            rs.getString("word"),
            text(rs, "phonetic"),
            text(rs, "definition"),
            text(rs, "translation"),
            text(rs, "pos"),
            integer(rs, "collins"),
            integer(rs, "oxford"),
            text(rs, "tag"),
            integer(rs, "bnc"),
            integer(rs, "frq"),
            text(rs, "exchange"),
            text(rs, "example")
        );
    }

    private static String text(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return value == null ? "" : value;
    }

    private static Integer integer(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static long parseLong(String value) {
        try {
            return value == null ? 0 : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Cannot close an ECDICT database connection", e);
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    /** An exclusive lock on a file, held by at most one import across all running app windows. */
    private static final class ImportLock implements AutoCloseable {
        private final FileChannel channel;

        private ImportLock(FileChannel channel) {
            this.channel = channel;
        }

        /** The lock; null when another import holds it. */
        static ImportLock tryAcquire(Path path) throws IOException {
            Files.createDirectories(path.getParent());
            FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                FileLock lock = channel.tryLock();
                if (lock != null) {
                    return new ImportLock(channel);
                }
            } catch (OverlappingFileLockException e) {
                // Held by another dictionary object of this app on the same file.
            } catch (IOException | RuntimeException e) {
                channel.close();
                throw e;
            }
            channel.close();
            return null;
        }

        /** Releases the lock; the lock file stays. */
        @Override
        public void close() {
            try {
                channel.close();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Cannot release the ECDICT import lock", e);
            }
        }
    }

    /**
     * An entry found through one of its inflected forms.
     *
     * @param kinds ECDICT's codes for what the form is: p past tense, d past participle, i present
     *              participle, 3 third person singular, r comparative, t superlative, s plural
     */
    public record BaseForm(EcdictRow row, String kinds) {
    }

    /**
     * A dictionary file being built. Rows are written in batches inside one transaction; nothing
     * is visible to lookups until {@link #commit}. Closing an import that was not committed
     * deletes its file.
     */
    public final class EcdictImport implements AutoCloseable {
        private final Connection connection;
        private final ImportLock lock;
        private final PreparedStatement insertRow;
        private final PreparedStatement insertForm;
        private int pendingRows;
        private int pendingForms;
        private boolean finished;

        private EcdictImport(Connection connection, ImportLock lock) throws SQLException {
            this.connection = connection;
            this.lock = lock;
            // The first row wins when ECDICT lists a word twice in different case.
            this.insertRow = connection.prepareStatement(
                "INSERT OR IGNORE INTO ecdict(" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            this.insertForm = connection.prepareStatement("""
                INSERT INTO ecdict_forms(form, lemma, kinds) VALUES (?, ?, ?)
                ON CONFLICT(form, lemma) DO UPDATE SET kinds = kinds || excluded.kinds
                """);
        }

        public void addRow(EcdictRow row) throws SQLException {
            insertRow.setString(1, row.word());
            setText(insertRow, 2, row.phonetic());
            setText(insertRow, 3, row.definition());
            insertRow.setString(4, row.translation());
            setText(insertRow, 5, row.pos());
            setInteger(insertRow, 6, row.collins());
            setInteger(insertRow, 7, row.oxford());
            setText(insertRow, 8, row.tag());
            setInteger(insertRow, 9, row.bnc());
            setInteger(insertRow, 10, row.frq());
            setText(insertRow, 11, row.exchange());
            setText(insertRow, 12, row.example());
            insertRow.addBatch();
            if (++pendingRows >= BATCH_SIZE) {
                insertRow.executeBatch();
                pendingRows = 0;
            }
        }

        /**
         * Records that {@code form} is an inflection of {@code lemma}, so a lookup of the form can
         * find the entry; {@code kinds} uses the codes of {@link BaseForm#kinds()}.
         */
        public void addForm(String form, String lemma, String kinds) throws SQLException {
            insertForm.setString(1, form);
            insertForm.setString(2, lemma);
            insertForm.setString(3, kinds);
            insertForm.addBatch();
            if (++pendingForms >= BATCH_SIZE) {
                insertForm.executeBatch();
                pendingForms = 0;
            }
        }

        /** Entries written so far; a word that was already added (ignoring case) is not counted twice. */
        public int countRows() throws SQLException {
            flush();
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT count(*) FROM ecdict")) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }

        /** Stores {@code metadata}, finishes the file and puts it in place of the current dictionary. */
        public void commit(EcdictMetadata metadata) throws SQLException, IOException {
            if (finished) {
                throw new IllegalStateException("This ECDICT import is already finished.");
            }
            flush();
            try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO ecdict_meta(key, value) VALUES (?, ?)")) {
                putMeta(statement, "source_path", metadata.sourcePath());
                putMeta(statement, "source_size", String.valueOf(metadata.sourceSize()));
                putMeta(statement, "source_modified_millis", String.valueOf(metadata.sourceModifiedMillis()));
                putMeta(statement, "row_count", String.valueOf(metadata.rowCount()));
                putMeta(statement, "skipped_rows", String.valueOf(metadata.skippedRows()));
                putMeta(statement, "imported_at", DateTimeUtil.toDatabase(metadata.importedAt()));
                putMeta(statement, "format", metadata.format());
                putMeta(statement, "duration_millis", String.valueOf(metadata.durationMillis()));
                statement.executeBatch();
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA user_version = " + FORMAT_VERSION);
                statement.execute("COMMIT");
            }
            finished = true;
            try {
                insertRow.close();
                insertForm.close();
                connection.close();
                replaceDatabase();
            } catch (SQLException | IOException | RuntimeException e) {
                deleteImportFileQuietly();
                throw e;
            } finally {
                lock.close();
                importing.set(false);
            }
        }

        /** Discards the import unless it was committed. */
        @Override
        public void close() {
            if (finished) {
                return;
            }
            finished = true;
            closeQuietly(connection);
            deleteImportFileQuietly();
            lock.close();
            importing.set(false);
        }

        private void flush() throws SQLException {
            if (pendingRows > 0) {
                insertRow.executeBatch();
                pendingRows = 0;
            }
            if (pendingForms > 0) {
                insertForm.executeBatch();
                pendingForms = 0;
            }
        }

        private static void putMeta(PreparedStatement statement, String key, String value) throws SQLException {
            statement.setString(1, key);
            statement.setString(2, value == null ? "" : value);
            statement.addBatch();
        }

        private static void setText(PreparedStatement statement, int index, String value) throws SQLException {
            if (value == null || value.isEmpty()) {
                statement.setNull(index, Types.VARCHAR);
            } else {
                statement.setString(index, value);
            }
        }

        private static void setInteger(PreparedStatement statement, int index, Integer value) throws SQLException {
            if (value == null) {
                statement.setNull(index, Types.INTEGER);
            } else {
                statement.setInt(index, value);
            }
        }
    }
}
