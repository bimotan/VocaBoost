package com.vocabtrainer.service.csv;

import java.io.IOException;

/** The text cannot be read as CSV, for example a quoted field is never closed. */
public class CsvFormatException extends IOException {
    private final int lineNumber;

    public CsvFormatException(int lineNumber, String problem) {
        this(lineNumber, problem, null);
    }

    public CsvFormatException(int lineNumber, String problem, Throwable cause) {
        super("Line " + lineNumber + ": " + problem, cause);
        this.lineNumber = lineNumber;
    }

    public int lineNumber() {
        return lineNumber;
    }
}
