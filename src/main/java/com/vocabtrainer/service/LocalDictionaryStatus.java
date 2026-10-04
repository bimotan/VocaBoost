package com.vocabtrainer.service;

import com.vocabtrainer.domain.EcdictMetadata;
import com.vocabtrainer.util.DateTimeUtil;
import com.vocabtrainer.util.Messages;

import java.util.List;

import static com.vocabtrainer.util.Messages.tr;

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
        String starter = tr("ecdict.status.starter", starterEntries);
        if (!ecdictImported()) {
            return Messages.sentences(List.of(tr("ecdict.status.notImported"), starter));
        }
        return Messages.sentences(List.of(tr("ecdict.status.imported", ecdict.rowCount(), ecdict.sourcePath(),
            DateTimeUtil.toDisplay(ecdict.importedAt()), ecdict.skippedRows()), starter));
    }
}
