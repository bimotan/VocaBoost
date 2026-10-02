package com.vocabtrainer.service.ecdict;

import com.vocabtrainer.domain.EcdictMetadata;
import com.vocabtrainer.repository.EcdictRepository;
import com.vocabtrainer.service.csv.CsvFormatException;
import com.vocabtrainer.service.csv.CsvReader;
import com.vocabtrainer.service.csv.CsvRecord;
import com.vocabtrainer.service.csv.TextEncoding;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Imports an ECDICT CSV (or a word-list CSV) into the dictionary file of {@link EcdictRepository},
 * so the CSV is parsed once instead of at every start.
 *
 * <p>The CSV is read with the shared {@link CsvReader}: ECDICT's quoting and literal "\n" separators,
 * a byte order mark, GBK files and headerless files all work, and a file that cannot be read fails
 * with its line number instead of silently leaving the dictionary empty. Columns are found as
 * {@link EcdictColumns} describes. The import streams the file, reports its progress and can be
 * canceled; the previous dictionary stays in use until the new one is complete.
 */
public class EcdictImportService {
    /** Entry rows {@link #check} reads. */
    static final int CHECK_ROWS = 200;

    private static final Logger LOGGER = Logger.getLogger(EcdictImportService.class.getName());
    private static final int PROGRESS_INTERVAL_ROWS = 10_000;
    private static final int CANCEL_CHECK_INTERVAL_ROWS = 1_000;

    private final EcdictRepository repository;
    private final Clock clock;

    public EcdictImportService(EcdictRepository repository) {
        this(repository, Clock.systemDefaultZone());
    }

    public EcdictImportService(EcdictRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** Whether a CSV needs importing; none of the answers reads the CSV. */
    public enum State {
        /** The dictionary was imported from this file and the file has not changed since. */
        UP_TO_DATE,
        /** Nothing is imported yet. */
        NOT_IMPORTED,
        /** The dictionary comes from another file, or this file's size or modification time changed. */
        CHANGED,
        /** There is no such file; an imported dictionary keeps working without it. */
        FILE_MISSING
    }

    /** What the current dictionary was imported from; empty when nothing is imported. */
    public Optional<EcdictMetadata> imported() {
        try {
            return repository.metadata();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read the imported ECDICT dictionary " + repository.databasePath(), e);
        }
    }

    /** Compares the file's path, size and modification time with what was imported. */
    public State state(Path csv) {
        Path file = csv.toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) {
            return State.FILE_MISSING;
        }
        Optional<EcdictMetadata> imported = imported();
        if (imported.isEmpty()) {
            return State.NOT_IMPORTED;
        }
        try {
            boolean same = imported.get().isImportOf(file.toString(), Files.size(file),
                Files.getLastModifiedTime(file).toMillis());
            return same ? State.UP_TO_DATE : State.CHANGED;
        } catch (IOException e) {
            // An import will report why the file cannot be read.
            return State.CHANGED;
        }
    }

    public boolean isImporting() {
        return repository.isImporting();
    }

    /**
     * Reads the encoding, the columns and the first {@value #CHECK_ROWS} rows, without importing.
     *
     * @throws IOException when the file is missing or cannot be read as CSV; the message names the line
     */
    public EcdictCheck check(Path csv) throws IOException {
        Path file = existingFile(csv);
        try (CsvReader reader = CsvReader.open(file)) {
            EcdictColumns columns = null;
            String format = "";
            int rows = 0;
            int usable = 0;
            String sample = "";
            CsvRecord record;
            while (rows < CHECK_ROWS && (record = reader.read()) != null) {
                if (record.isBlank()) {
                    continue;
                }
                if (columns == null) {
                    EcdictColumns.Detection detection = EcdictColumns.detect(record);
                    columns = detection.columns();
                    format = format(reader.encoding().orElse(TextEncoding.UTF_8), reader, columns);
                    if (detection.headerRow()) {
                        continue;
                    }
                }
                rows++;
                String word = columns.word(record);
                String translation = columns.translation(record);
                if (!word.isEmpty() && !translation.isEmpty()) {
                    usable++;
                    if (sample.isEmpty()) {
                        sample = word + ": " + EcdictTranslationCleaner.clean(translation).meaning();
                    }
                }
            }
            if (columns == null) {
                throw new IOException("The ECDICT CSV is empty: " + file);
            }
            return new EcdictCheck(format, rows, usable, sample);
        } catch (CsvFormatException e) {
            throw new IOException("Cannot read ECDICT CSV " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * Imports {@code csv} as the new dictionary. Rows without a word or a Chinese meaning are skipped;
     * when a word appears twice (ignoring case) the first row wins.
     *
     * @param progress  called on this thread every few thousand rows and at the end
     * @param cancelled polled every thousand rows; when it says true the import stops with a
     *                  {@link CancellationException} and the previous dictionary stays as it was
     * @return what was imported
     * @throws IOException when the file cannot be read, is not CSV, or has no entries; the
     *                     previous dictionary stays as it was
     */
    public EcdictMetadata importCsv(Path csv, Consumer<EcdictImportProgress> progress, BooleanSupplier cancelled)
        throws IOException, SQLException {
        long started = System.nanoTime();
        Path file = existingFile(csv);
        long size = Files.size(file);
        long modified = Files.getLastModifiedTime(file).toMillis();
        progress.accept(new EcdictImportProgress(0, 0, size));
        TextEncoding encoding;
        try {
            // Reads the whole file once; a file that is not valid UTF-8 is GBK.
            encoding = TextEncoding.detect(file);
        } catch (IOException e) {
            throw new IOException("Cannot read ECDICT CSV " + file + ": " + e.getMessage(), e);
        }
        stopIfCancelled(cancelled);
        try (CountingInputStream counting = new CountingInputStream(Files.newInputStream(file));
             CsvReader reader = CsvReader.open(counting, encoding);
             EcdictRepository.EcdictImport target = repository.beginImport()) {
            EcdictColumns columns = null;
            String format = "";
            long records = 0;
            CsvRecord record;
            while ((record = reader.read()) != null) {
                if (record.isBlank()) {
                    continue;
                }
                if (columns == null) {
                    EcdictColumns.Detection detection = EcdictColumns.detect(record);
                    columns = detection.columns();
                    format = format(encoding, reader, columns);
                    if (detection.headerRow()) {
                        continue;
                    }
                }
                records++;
                String word = columns.word(record);
                if (!word.isEmpty()) {
                    if (!columns.translation(record).isEmpty()) {
                        target.addRow(columns.row(record));
                    }
                    addForms(target, word, columns.exchange(record));
                }
                if (records % CANCEL_CHECK_INTERVAL_ROWS == 0) {
                    stopIfCancelled(cancelled);
                }
                if (records % PROGRESS_INTERVAL_ROWS == 0) {
                    progress.accept(new EcdictImportProgress(records, counting.count(), size));
                }
            }
            if (columns == null) {
                throw new IOException("The ECDICT CSV is empty: " + file);
            }
            int rows = target.countRows();
            if (rows == 0) {
                throw new IOException("No dictionary entries in " + file
                    + ": no row has both a word and a Chinese meaning. " + format);
            }
            stopIfCancelled(cancelled);
            EcdictMetadata metadata = new EcdictMetadata(file.toString(), size, modified, rows,
                (int) Math.min(Integer.MAX_VALUE, records - rows), LocalDateTime.now(clock), format,
                (System.nanoTime() - started) / 1_000_000);
            target.commit(metadata);
            progress.accept(new EcdictImportProgress(records, size, size));
            LOGGER.info("Imported " + rows + " ECDICT entries from " + file + " in " + metadata.durationMillis() + " ms");
            return metadata;
        } catch (CsvFormatException e) {
            throw new IOException("Cannot import ECDICT CSV " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * Deletes the imported dictionary.
     *
     * @throws IllegalStateException while an import runs, or when the file cannot be deleted
     */
    public void delete() {
        try {
            repository.delete();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot delete the imported ECDICT dictionary " + repository.databasePath(), e);
        }
    }

    /**
     * Records what ECDICT's exchange field says about inflections, so a lookup of a form finds its
     * entry: a base form lists its forms ("p:abandoned/d:abandoned/i:abandoning/3:abandons"), and a
     * row that is itself a form names its base form ("0:abandon/1:p").
     */
    private static void addForms(EcdictRepository.EcdictImport target, String word, String exchange) throws SQLException {
        if (exchange.isEmpty()) {
            return;
        }
        String lemma = null;
        String lemmaKinds = "";
        for (String item : exchange.split("/")) {
            int colon = item.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String code = item.substring(0, colon).strip();
            String value = item.substring(colon + 1).strip();
            if (value.isEmpty()) {
                continue;
            }
            switch (code) {
                case "0" -> lemma = value;
                case "1" -> lemmaKinds = value;
                case "p", "d", "i", "3", "r", "t", "s" -> {
                    if (!value.equalsIgnoreCase(word)) {
                        target.addForm(value, word, code);
                    }
                }
                default -> {
                    // Other codes are not inflections.
                }
            }
        }
        if (lemma != null && !lemma.equalsIgnoreCase(word)) {
            target.addForm(word, lemma, lemmaKinds);
        }
    }

    private static Path existingFile(Path csv) throws IOException {
        Path file = csv.toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) {
            throw new IOException("ECDICT CSV not found: " + file);
        }
        return file;
    }

    private static String format(TextEncoding encoding, CsvReader reader, EcdictColumns columns) {
        return "Encoding: " + encoding.displayName() + " | Delimiter: " + reader.delimiterName()
            + " | Columns: " + columns.description();
    }

    private static void stopIfCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("The ECDICT import was canceled.");
        }
    }

    /** Counts the bytes read, for progress. */
    private static final class CountingInputStream extends FilterInputStream {
        private long count;

        private CountingInputStream(InputStream in) {
            super(in);
        }

        long count() {
            return count;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                count++;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                count += read;
            }
            return read;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(n);
            count += skipped;
            return skipped;
        }
    }
}
