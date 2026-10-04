package com.vocabtrainer.service;

import com.vocabtrainer.util.Messages;

import java.util.List;

import static com.vocabtrainer.util.Messages.tr;

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
        StringBuilder builder = new StringBuilder(tr("import.result.counts", importedCount, skippedCount));
        if (meaningsFilled > 0) {
            builder = new StringBuilder(Messages.sentences(List.of(builder.toString(),
                tr("import.result.meanings", dictionary, meaningsFilled))));
        }
        if (!messages.isEmpty()) {
            builder.append(System.lineSeparator()).append(String.join(System.lineSeparator(), messages));
        }
        return builder.toString();
    }
}
