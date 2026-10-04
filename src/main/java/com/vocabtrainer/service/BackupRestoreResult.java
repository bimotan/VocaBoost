package com.vocabtrainer.service;

import java.util.List;

import static com.vocabtrainer.util.Messages.tr;

/** What a JSON backup restore changed; {@code invalidRows} lists each row it could not use and why. */
public record BackupRestoreResult(
    int formatVersion,
    int wordsInserted,
    int wordsUpdated,
    int wordsSkipped,
    int logsInserted,
    int duplicateLogsSkipped,
    int dailyGoalsRestored,
    int achievementsRestored,
    List<String> invalidRows
) {
    private static final int MAX_LISTED_INVALID_ROWS = 20;

    public BackupRestoreResult {
        invalidRows = List.copyOf(invalidRows);
    }

    public String toSummary() {
        String newLine = System.lineSeparator();
        StringBuilder builder = new StringBuilder();
        builder.append(tr("backup.result.restored", String.valueOf(formatVersion))).append(newLine)
            .append(tr("backup.result.words", wordsInserted, wordsUpdated, wordsSkipped)).append(newLine)
            .append(tr("backup.result.logs", logsInserted, duplicateLogsSkipped)).append(newLine)
            .append(tr("backup.result.goals", dailyGoalsRestored, achievementsRestored));
        if (!invalidRows.isEmpty()) {
            builder.append(newLine).append(tr("backup.result.invalid", invalidRows.size()));
            invalidRows.stream().limit(MAX_LISTED_INVALID_ROWS).forEach(row -> builder.append(newLine).append(row));
            if (invalidRows.size() > MAX_LISTED_INVALID_ROWS) {
                builder.append(newLine).append(tr("backup.result.moreInvalid", invalidRows.size() - MAX_LISTED_INVALID_ROWS));
            }
        }
        return builder.toString();
    }
}
