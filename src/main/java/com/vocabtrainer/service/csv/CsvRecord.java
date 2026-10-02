package com.vocabtrainer.service.csv;

import java.util.List;

/**
 * One CSV record. {@code lineNumber} is the physical line (1-based) the record starts on, which is
 * what a text editor shows, even when earlier records span several lines.
 */
public record CsvRecord(List<String> fields, int lineNumber) {
    public CsvRecord {
        fields = List.copyOf(fields);
    }

    public int size() {
        return fields.size();
    }

    /** The field at {@code index}, or "" when the record is shorter. */
    public String get(int index) {
        return index >= 0 && index < fields.size() ? fields.get(index) : "";
    }

    /** True when every field is empty or whitespace, like the ",,,," rows spreadsheets leave behind. */
    public boolean isBlank() {
        return fields.stream().allMatch(String::isBlank);
    }
}
