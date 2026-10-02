package com.vocabtrainer.service;

import com.vocabtrainer.domain.EcdictMetadata;
import com.vocabtrainer.util.DateTimeUtil;

import java.util.Locale;

/**
 * What the offline dictionaries hold.
 *
 * @param ecdict         what the ECDICT dictionary was imported from; null when nothing is imported
 * @param starterEntries entries of the bundled GRE starter dictionary
 */
public record LocalDictionaryStatus(EcdictMetadata ecdict, int starterEntries) {
    public boolean ecdictImported() {
        return ecdict != null && ecdict.rowCount() > 0;
    }

    /**
     * For example "ECDICT: 770,611 entries from /home/me/ecdict.csv, imported 2026-10-02 09:15
     * (skipped rows: 3). Bundled GRE starter: 215 entries."
     */
    public String toDisplayText() {
        String starter = String.format(Locale.ROOT, "Bundled GRE starter: %,d entries.", starterEntries);
        if (!ecdictImported()) {
            return "ECDICT: not imported. " + starter;
        }
        return String.format(Locale.ROOT, "ECDICT: %,d entries from %s, imported %s (skipped rows: %,d). %s",
            ecdict.rowCount(), ecdict.sourcePath(), DateTimeUtil.toDisplay(ecdict.importedAt()),
            ecdict.skippedRows(), starter);
    }
}
