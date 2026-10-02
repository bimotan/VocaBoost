package com.vocabtrainer.service;

import com.vocabtrainer.service.csv.WordColumns;
import com.vocabtrainer.service.wordlist.WordListFile;

import java.util.List;

/**
 * What importing a word list would do, plus how the file was read: {@code encoding} such as
 * "UTF-8" or "GBK/GB18030", {@code delimiter} such as "comma", {@code columns}, the word fields in
 * file order and where their names came from, and {@code format}, the Anki headers the file has
 * (empty for other files).
 *
 * @param needMeaningCount rows without a Chinese meaning, whose meaning the import looks up in the
 *                         dictionary; they are not counted as importable
 * @param mapping          which file column holds which word field
 * @param fileColumns      the file's columns, with their names and first values
 * @param rows             the first rows as they would be imported
 */
public record ImportPreview(
    int totalRows,
    int importableCount,
    int duplicateCount,
    int invalidCount,
    int needMeaningCount,
    List<String> firstErrors,
    String encoding,
    String delimiter,
    String columns,
    String format,
    WordColumns mapping,
    List<WordListFile.FileColumn> fileColumns,
    List<Row> rows
) {
    public ImportPreview {
        firstErrors = List.copyOf(firstErrors);
        fileColumns = List.copyOf(fileColumns);
        rows = List.copyOf(rows);
    }

    /**
     * One of the first rows: its fields as they would be imported, and what happens to it, such as
     * "Import", "Duplicate" or why it is skipped.
     */
    public record Row(int line, String english, String chinese, String partOfSpeech, String phonetic,
                      String example, String note, String tags, String status) {
    }

    public String toSummary() {
        StringBuilder builder = new StringBuilder();
        builder.append("Rows: ").append(totalRows)
            .append(", importable: ").append(importableCount);
        if (needMeaningCount > 0) {
            builder.append(", meanings to look up: ").append(needMeaningCount);
        }
        builder.append(", duplicates: ").append(duplicateCount)
            .append(", invalid: ").append(invalidCount);
        builder.append(System.lineSeparator())
            .append("Encoding: ").append(encoding)
            .append(" | Delimiter: ").append(delimiter)
            .append(" | Columns: ").append(columns);
        if (!format.isBlank()) {
            builder.append(" | ").append(format);
        }
        if (!firstErrors.isEmpty()) {
            builder.append(System.lineSeparator()).append("First errors:")
                .append(System.lineSeparator())
                .append(String.join(System.lineSeparator(), firstErrors));
        }
        return builder.toString();
    }
}
