package com.vocabtrainer.service;

import java.util.List;

/**
 * What an import did.
 *
 * @param meaningsFilled words imported with a meaning looked up in {@code dictionary}, because the
 *                       file had none for them
 * @param dictionary     the dictionaries asked, such as "the local dictionary"; empty when none was
 */
public record ImportResult(int importedCount, int skippedCount, List<String> messages, int meaningsFilled,
                           String dictionary) {
    public ImportResult {
        messages = List.copyOf(messages);
    }

    public ImportResult(int importedCount, int skippedCount, List<String> messages) {
        this(importedCount, skippedCount, messages, 0, "");
    }

    public String toSummary() {
        StringBuilder builder = new StringBuilder();
        builder.append("Imported ").append(importedCount).append(", skipped ").append(skippedCount).append(".");
        if (meaningsFilled > 0) {
            builder.append(" Meanings from ").append(dictionary).append(": ").append(meaningsFilled).append(".");
        }
        if (!messages.isEmpty()) {
            builder.append(System.lineSeparator()).append(String.join(System.lineSeparator(), messages));
        }
        return builder.toString();
    }
}
