package com.vocabtrainer.service.csv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvReaderTest {
    @TempDir
    Path tempDir;

    @Test
    void quotedFieldsHoldDelimitersQuotesAndLineBreaks() throws IOException {
        List<CsvRecord> records = readAll("""
            english,chinese,example
            lucid,"清晰的, 易懂的","He said ""lucid"" twice."
            alacrity,"欣然
            敏捷",""
            abate,减弱,"line one\r
            line two"
            """);

        assertEquals(List.of("english", "chinese", "example"), records.get(0).fields());
        assertEquals(List.of("lucid", "清晰的, 易懂的", "He said \"lucid\" twice."), records.get(1).fields());
        assertEquals(List.of("alacrity", "欣然\n敏捷", ""), records.get(2).fields());
        // CRLF inside a field comes back as LF.
        assertEquals(List.of("abate", "减弱", "line one\nline two"), records.get(3).fields());
        assertEquals(4, records.size());
    }

    @Test
    void recordsKnowThePhysicalLineTheyStartOn() throws IOException {
        List<CsvRecord> records = readAll("a,b\r\n\r\nc,\"1\n2\n3\"\rd,e\n\nf,g");

        assertEquals(List.of(1, 3, 6, 8), records.stream().map(CsvRecord::lineNumber).toList());
        assertEquals(List.of("c", "1\n2\n3"), records.get(1).fields());
        assertEquals(List.of("d", "e"), records.get(2).fields());
    }

    @Test
    void emptyFieldsAndAQuoteInsideAnUnquotedFieldAreKept() throws IOException {
        List<CsvRecord> records = readAll("a,,c,\nsay \"hi\",x\nabate, \"减弱, 减少\"\n");

        assertEquals(List.of("a", "", "c", ""), records.get(0).fields());
        assertEquals(List.of("say \"hi\"", "x"), records.get(1).fields());
        // Spaces before an opening quote do not turn the quotes into text.
        assertEquals(List.of("abate", "减弱, 减少"), records.get(2).fields());
    }

    @Test
    void aLeadingByteOrderMarkIsSkipped() throws IOException {
        List<CsvRecord> records = readAll("\uFEFFenglish,chinese\nlucid,清晰的\n");

        assertEquals("english", records.get(0).get(0));
    }

    @Test
    void aQuoteThatIsNeverClosedIsReportedWithItsLine() throws IOException {
        try (CsvReader reader = CsvReader.open(new StringReader("a,b\nc,\"open\nd,e\n"))) {
            assertEquals(List.of("a", "b"), reader.read().fields());

            CsvFormatException error = assertThrows(CsvFormatException.class, reader::read);

            assertEquals(2, error.lineNumber());
            assertTrue(error.getMessage().startsWith("Line 2: a quoted field is not closed"), error.getMessage());
        }
    }

    @Test
    void sniffsCommaTabAndSemicolon() throws IOException {
        assertEquals(',', delimiterOf("english,chinese,pos\nabate,减弱; 减少,verb\n"));
        assertEquals('\t', delimiterOf("english\tchinese\nabate\t减弱, 减少\nlucid\t清晰的\n"));
        assertEquals(';', delimiterOf("english;chinese;pos\nabate;减弱, 减少;verb\nlucid;清晰的;adj\n"));
        // The starter CSV: semicolons inside quoted meanings and unquoted tags.
        assertEquals(',', delimiterOf("""
            english,chinese,pos,example,tags
            abate,"减弱; 减少",verb,"The storm began to abate.",gre;starter
            lucid,"清晰的",adjective,"The talk was lucid.",gre;starter
            """));
        // Anki's plain-text export with tab-separated notes.
        assertEquals('\t', delimiterOf("abate\t减弱；减少\nlucid\t清晰的<br>易懂的\ncandid\t坦率的\n"));
        // A one-column word list.
        assertEquals(',', delimiterOf("abate\nlucid\n"));
        assertEquals(',', delimiterOf(""));
    }

    @Test
    void aGivenDelimiterIsUsedAsIs() throws IOException {
        try (CsvReader reader = CsvReader.open(new StringReader("a;b,c\n"), ';')) {
            assertEquals(List.of("a", "b,c"), reader.read().fields());
            assertNull(reader.read());
        }
    }

    @Test
    void sniffingIgnoresARecordCutOffAtTheEndOfTheSample() {
        // 19 complete tab records, then one cut off inside a quoted field holding many tabs.
        StringBuilder sample = new StringBuilder();
        for (int i = 0; i < 19; i++) {
            sample.append("word").append(i).append("\tmeaning\n");
        }
        sample.append("cut\t\"\t\t\t\t");

        assertEquals('\t', CsvReader.sniffDelimiter(sample.toString(), false));
    }

    @Test
    void readsLargeFilesRecordByRecord() throws IOException {
        Path file = tempDir.resolve("large.csv");
        int rows = 200_000;
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write("english,chinese,pos,example,tags\n");
            for (int i = 0; i < rows; i++) {
                writer.write("word" + i + ",\"释义 " + i + "\",noun,\"An example, with a comma.\",bulk\n");
            }
        }

        int[] count = new int[1];
        CsvRecord[] last = new CsvRecord[1];
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            try (CsvReader reader = CsvReader.open(file)) {
                CsvRecord record;
                while ((record = reader.read()) != null) {
                    count[0]++;
                    last[0] = record;
                }
            }
        });

        assertEquals(rows + 1, count[0]);
        assertEquals(List.of("word199999", "释义 199999", "noun", "An example, with a comma.", "bulk"), last[0].fields());
        assertEquals(rows + 1, last[0].lineNumber());
    }

    @Test
    void readsAheadOnlyABufferNotTheWholeText() throws IOException {
        GeneratedCsv source = new GeneratedCsv(1_000_000);
        try (CsvReader reader = CsvReader.open(source)) {
            assertEquals(List.of("word0", "meaning 0"), reader.read().fields());
            assertTrue(source.charsHandedOut < 200_000, "Read ahead " + source.charsHandedOut + " chars");

            int count = 1;
            while (reader.read() != null) {
                count++;
            }
            assertEquals(1_000_000, count);
        }
    }

    private static char delimiterOf(String text) throws IOException {
        try (CsvReader reader = CsvReader.open(new StringReader(text))) {
            return reader.delimiter();
        }
    }

    private static List<CsvRecord> readAll(String text) throws IOException {
        List<CsvRecord> records = new ArrayList<>();
        try (CsvReader reader = CsvReader.open(new StringReader(text))) {
            CsvRecord record;
            while ((record = reader.read()) != null) {
                records.add(record);
            }
        }
        return records;
    }

    /** Produces "word{i},meaning {i}" rows on demand, so the text never exists in memory as a whole. */
    private static final class GeneratedCsv extends Reader {
        private final int rows;
        private int nextRow;
        private String pending = "";
        private int pendingPosition;
        private long charsHandedOut;

        GeneratedCsv(int rows) {
            this.rows = rows;
        }

        @Override
        public int read(char[] target, int offset, int length) {
            if (pendingPosition == pending.length()) {
                if (nextRow == rows) {
                    return -1;
                }
                pending = "word" + nextRow + ",meaning " + nextRow + "\n";
                pendingPosition = 0;
                nextRow++;
            }
            int count = Math.min(length, pending.length() - pendingPosition);
            pending.getChars(pendingPosition, pendingPosition + count, target, offset);
            pendingPosition += count;
            charsHandedOut += count;
            return count;
        }

        @Override
        public void close() {
        }
    }
}
