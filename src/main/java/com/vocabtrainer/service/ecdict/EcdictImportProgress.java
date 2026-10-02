package com.vocabtrainer.service.ecdict;

import java.util.Locale;

/**
 * How far an ECDICT import is.
 *
 * @param rows       CSV records read so far
 * @param bytesRead  bytes of the file read so far (the reader reads a little ahead of the records)
 * @param totalBytes size of the file
 */
public record EcdictImportProgress(long rows, long bytesRead, long totalBytes) {
    /** Between 0 and 1. */
    public double fraction() {
        return totalBytes <= 0 ? 0 : Math.min(1.0, (double) bytesRead / totalBytes);
    }

    /** For example "Read 120,000 rows (35% of 79.5 MB)"; before the first row "Checking the file (79.5 MB)...". */
    public String toDisplayText() {
        double megabytes = totalBytes / (1024.0 * 1024.0);
        if (rows == 0) {
            return String.format(Locale.ROOT, "Checking the file (%.1f MB)...", megabytes);
        }
        return String.format(Locale.ROOT, "Read %,d rows (%d%% of %.1f MB)", rows, Math.round(fraction() * 100), megabytes);
    }
}
