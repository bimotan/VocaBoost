package com.vocabtrainer.service;

import java.util.List;

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
        builder.append("Backup restored (format version ").append(formatVersion).append(").").append(newLine)
            .append("Words: ").append(wordsInserted).append(" added, ")
            .append(wordsUpdated).append(" updated with the backup's progress, ")
            .append(wordsSkipped).append(" already in the deck and left unchanged.").append(newLine)
            .append("Review logs: ").append(logsInserted).append(" added, ")
            .append(duplicateLogsSkipped).append(" already present.").append(newLine)
            .append("Daily goal days added: ").append(dailyGoalsRestored)
            .append(", achievements added: ").append(achievementsRestored).append(".");
        if (!invalidRows.isEmpty()) {
            builder.append(newLine).append("Invalid rows skipped: ").append(invalidRows.size());
            invalidRows.stream().limit(MAX_LISTED_INVALID_ROWS).forEach(row -> builder.append(newLine).append(row));
            if (invalidRows.size() > MAX_LISTED_INVALID_ROWS) {
                builder.append(newLine).append("... and ").append(invalidRows.size() - MAX_LISTED_INVALID_ROWS)
                    .append(" more (see the log file).");
            }
        }
        return builder.toString();
    }
}
