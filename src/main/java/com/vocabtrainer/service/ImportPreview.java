package com.vocabtrainer.service;

import java.util.List;

/**
 * What importing a GRE CSV would do, plus how the file was read: {@code encoding} such as "UTF-8"
 * or "GBK/GB18030", {@code delimiter} such as "comma", and {@code columns}, the word fields in file
 * order and whether they came from a header row.
 */
public record ImportPreview(
    int totalRows,
    int importableCount,
    int duplicateCount,
    int invalidCount,
    List<String> firstErrors,
    String encoding,
    String delimiter,
    String columns
) {
    public String toSummary() {
        StringBuilder builder = new StringBuilder();
        builder.append("Rows: ").append(totalRows)
            .append(", importable: ").append(importableCount)
            .append(", duplicates: ").append(duplicateCount)
            .append(", invalid: ").append(invalidCount);
        builder.append(System.lineSeparator())
            .append("Encoding: ").append(encoding)
            .append(" | Delimiter: ").append(delimiter)
            .append(" | Columns: ").append(columns);
        if (!firstErrors.isEmpty()) {
            builder.append(System.lineSeparator()).append("First errors:")
                .append(System.lineSeparator())
                .append(String.join(System.lineSeparator(), firstErrors));
        }
        return builder.toString();
    }
}
