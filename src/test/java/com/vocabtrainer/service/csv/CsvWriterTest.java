package com.vocabtrainer.service.csv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CsvWriterTest {
    /** Cells a spreadsheet would run as formulas, and cells that only look a bit like them. */
    private static final List<String> TRICKY_CELLS = List.of(
        "=HYPERLINK(\"http://x\",\"点击\")", "+cmd|' /C calc'!A0", "-ness", "@SUM(A1:A2)", "\tindented",
        "\rreturn", "\nnew line", "'-already quoted", "''=twice", "'plain apostrophe", "-3", "+1.5", "1-2", "a=b",
        "清晰的, 易懂的", "say \"hi\"", "line one\nline two", " padded ", "", "-", "=");

    @TempDir
    Path tempDir;

    @Test
    void filesStartWithAByteOrderMarkAndUseCrlf() throws IOException {
        Path file = tempDir.resolve("out.csv");
        try (CsvWriter writer = CsvWriter.create(file)) {
            writer.writeRow("english", "chinese");
            writer.writeRow("lucid", "清晰的");
        }

        byte[] content = Files.readAllBytes(file);
        assertArrayEquals(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, Arrays.copyOf(content, 3));
        assertEquals("\uFEFFenglish,chinese\r\nlucid,清晰的\r\n", new String(content, StandardCharsets.UTF_8));
    }

    @Test
    void quotesOnlyWhatNeedsQuoting() {
        assertEquals("lucid", CsvWriter.encode("lucid", ','));
        assertEquals("\"清晰的, 易懂的\"", CsvWriter.encode("清晰的, 易懂的", ','));
        assertEquals("清晰的, 易懂的", CsvWriter.encode("清晰的, 易懂的", '\t'));
        assertEquals("\"say \"\"hi\"\"\"", CsvWriter.encode("say \"hi\"", ','));
        assertEquals("\"line one\nline two\"", CsvWriter.encode("line one\nline two", ','));
        assertEquals("\" padded \"", CsvWriter.encode(" padded ", ','));
        assertEquals("", CsvWriter.encode(null, ','));
    }

    @Test
    void formulaCellsGetAnApostropheAndPlainNumbersDoNot() {
        assertEquals("\"'=HYPERLINK(\"\"http://x\"\",\"\"点击\"\")\"", CsvWriter.encode("=HYPERLINK(\"http://x\",\"点击\")", ','));
        assertEquals("'+cmd|' /C calc'!A0", CsvWriter.encode("+cmd|' /C calc'!A0", ','));
        assertEquals("'-ness", CsvWriter.encode("-ness", ','));
        assertEquals("'@SUM(A1:A2)", CsvWriter.encode("@SUM(A1:A2)", ','));
        assertEquals("'\tindented", CsvWriter.encode("\tindented", ','));
        assertEquals("\"'\rreturn\"", CsvWriter.encode("\rreturn", ','));
        // An apostrophe that is already there could be mistaken for ours, so it gets one more.
        assertEquals("''-already quoted", CsvWriter.encode("'-already quoted", ','));
        assertEquals("'plain apostrophe", CsvWriter.encode("'plain apostrophe", ','));
        assertEquals("-3", CsvWriter.encode("-3", ','));
        assertEquals("+1.5", CsvWriter.encode("+1.5", ','));
        assertEquals("a=b", CsvWriter.encode("a=b", ','));
    }

    @Test
    void readingBackAndUnprotectingGivesTheOriginalCells() throws IOException {
        StringWriter text = new StringWriter();
        CsvWriter writer = new CsvWriter(text, ',');
        for (String cell : TRICKY_CELLS) {
            writer.writeRow("key", cell, "end");
        }

        try (CsvReader reader = CsvReader.open(new StringReader(text.toString()))) {
            for (String cell : TRICKY_CELLS) {
                CsvRecord record = reader.read();
                // CR inside a field is read back as a line break.
                String expected = cell.replace("\r", "\n");
                assertEquals(List.of("key", expected, "end"),
                    record.fields().stream().map(FormulaGuard::unprotect).map(value -> value.replace("\r", "\n")).toList(),
                    "cell " + cell);
            }
            assertNull(reader.read());
        }
    }
}
