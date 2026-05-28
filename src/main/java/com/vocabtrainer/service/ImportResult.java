package com.vocabtrainer.service;

import java.util.List;

public record ImportResult(int importedCount, int skippedCount, List<String> messages) {
    public String toSummary() {
        StringBuilder builder = new StringBuilder();
        builder.append("Imported ").append(importedCount).append(", skipped ").append(skippedCount).append(".");
        if (!messages.isEmpty()) {
            builder.append(System.lineSeparator()).append(String.join(System.lineSeparator(), messages));
        }
        return builder.toString();
    }
}
