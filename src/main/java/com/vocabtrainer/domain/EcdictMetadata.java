package com.vocabtrainer.domain;

import java.time.LocalDateTime;

/**
 * What the imported ECDICT dictionary was imported from. The file's size and modification time
 * tell whether the CSV changed since, without reading it.
 *
 * @param sourcePath           absolute path of the imported CSV
 * @param sourceModifiedMillis the CSV's last-modified time, in milliseconds since the epoch
 * @param rowCount             entries in the dictionary
 * @param skippedRows          CSV rows that were not imported: no word, no translation, or a word seen before
 * @param format               encoding, delimiter and columns the CSV was read with
 * @param durationMillis       how long the import took
 */
public record EcdictMetadata(
    String sourcePath,
    long sourceSize,
    long sourceModifiedMillis,
    int rowCount,
    int skippedRows,
    LocalDateTime importedAt,
    String format,
    long durationMillis
) {
    /** True when the CSV at {@code path} still has the size and modification time it had when imported. */
    public boolean isImportOf(String path, long size, long modifiedMillis) {
        return sourcePath.equals(path) && sourceSize == size && sourceModifiedMillis == modifiedMillis;
    }
}
