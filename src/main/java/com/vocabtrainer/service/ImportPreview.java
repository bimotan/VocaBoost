package com.vocabtrainer.service;

import java.util.List;

public record ImportPreview(
    int totalRows,
    int importableCount,
    int duplicateCount,
    int invalidCount,
    List<String> firstErrors
) {
    public String toSummary() {
        StringBuilder builder = new StringBuilder();
        builder.append("Rows: ").append(totalRows)
            .append(", importable: ").append(importableCount)
            .append(", duplicates: ").append(duplicateCount)
            .append(", invalid: ").append(invalidCount);
        if (!firstErrors.isEmpty()) {
            builder.append(System.lineSeparator()).append("First errors:")
                .append(System.lineSeparator())
                .append(String.join(System.lineSeparator(), firstErrors));
        }
        return builder.toString();
    }
}
