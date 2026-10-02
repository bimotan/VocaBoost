package com.vocabtrainer.service.csv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextEncodingTest {
    private static final Charset GBK = Charset.forName("GBK");

    @TempDir
    Path tempDir;

    @Test
    void utf8WithoutByteOrderMark() throws IOException {
        Path file = write("plain.csv", "english,chinese\nlucid,清晰的\n".getBytes(StandardCharsets.UTF_8));

        assertEquals(TextEncoding.UTF_8, TextEncoding.detect(file));
        assertEquals(List.of(List.of("english", "chinese"), List.of("lucid", "清晰的")), readAll(file));
    }

    @Test
    void utf8WithByteOrderMarkAsExcelSavesCsvUtf8() throws IOException {
        Path file = write("bom.csv", bytes(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
            "english,chinese\nlucid,清晰的\n".getBytes(StandardCharsets.UTF_8)));

        TextEncoding encoding = TextEncoding.detect(file);

        assertEquals(new TextEncoding(StandardCharsets.UTF_8, 3), encoding);
        assertEquals("UTF-8 with BOM", encoding.displayName());
        assertEquals(List.of("english", "chinese"), readAll(file).get(0));
    }

    @Test
    void gbkAsChineseExcelSavesCsv() throws IOException {
        Path file = write("gbk.csv", "english,chinese\nlucid,清晰的\nabate,减弱；减少\n".getBytes(GBK));

        TextEncoding encoding = TextEncoding.detect(file);

        assertEquals(TextEncoding.GB18030, encoding.charset());
        assertEquals("GBK/GB18030", encoding.displayName());
        assertEquals(List.of("abate", "减弱；减少"), readAll(file).get(2));
    }

    @Test
    void gbkFarIntoAnOtherwiseAsciiFileIsStillFound() throws IOException {
        StringBuilder text = new StringBuilder("english,chinese\n");
        for (int i = 0; i < 20_000; i++) {
            text.append("word").append(i).append(",meaning\n");
        }
        text.append("lucid,清晰的\n");
        Path file = write("late-gbk.csv", text.toString().getBytes(GBK));

        assertEquals(TextEncoding.GB18030, TextEncoding.detect(file).charset());
        List<List<String>> records = readAll(file);
        assertEquals(List.of("lucid", "清晰的"), records.get(records.size() - 1));
    }

    @Test
    void utf16WithByteOrderMarkAsExcelSavesUnicodeText() throws IOException {
        Path file = write("unicode.txt", bytes(new byte[] {(byte) 0xFF, (byte) 0xFE},
            "english\tchinese\r\nlucid\t清晰的\r\n".getBytes(StandardCharsets.UTF_16LE)));

        assertEquals(new TextEncoding(StandardCharsets.UTF_16LE, 2), TextEncoding.detect(file));
        try (CsvReader reader = CsvReader.open(file)) {
            assertEquals('\t', reader.delimiter());
            assertEquals(List.of("english", "chinese"), reader.read().fields());
            assertEquals(List.of("lucid", "清晰的"), reader.read().fields());
        }
    }

    @Test
    void aLongGbkFileIsGb18030() throws IOException {
        String starter;
        try (var in = TextEncodingTest.class.getResourceAsStream("/data/gre_starter_sample.csv")) {
            starter = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Path file = write("starter-gbk.csv", starter.getBytes(GBK));

        assertEquals(TextEncoding.GB18030, TextEncoding.detect(file).charset());
        assertEquals(List.of("abate", "减弱; 减少", "verb", "The storm began to abate.", "gre;starter"), readAll(file).get(1));
    }

    @Test
    void utf8WithOneDamagedByteAfterMuchChineseIsUtf8AndFailsAtThatLine() throws IOException {
        String chinese = "清晰的".repeat(TextEncoding.UTF_8_EVIDENCE / 3 + 1);
        Path file = write("damaged.csv", bytes(
            ("english,chinese\nlucid," + chinese + "\n").getBytes(StandardCharsets.UTF_8),
            new byte[] {'c', 'a', 'f', 'e', ',', (byte) 0xE9, 's', '\n'}));

        try (CsvReader reader = CsvReader.open(file)) {
            assertEquals(TextEncoding.UTF_8, reader.encoding().orElseThrow());
            reader.read();
            assertEquals(List.of("lucid", chinese), reader.read().fields());

            CsvFormatException error = assertThrows(CsvFormatException.class, reader::read);

            assertEquals("Line 3: the text is not valid UTF-8 (a character on this line is damaged or in another"
                + " encoding)", error.getMessage());
        }
    }

    @Test
    void textThatIsNeitherUtf8NorGb18030FailsAtTheLineWithTheBadBytes() throws IOException {
        Path file = write("broken.csv", bytes(
            "english,chinese\nlucid,clear\n".getBytes(StandardCharsets.US_ASCII),
            new byte[] {'b', 'a', 'd', ',', (byte) 0xFF, '\n'}));

        try (CsvReader reader = CsvReader.open(file)) {
            assertEquals(TextEncoding.GB18030, reader.encoding().orElseThrow().charset());
            reader.read();
            reader.read();

            CsvFormatException error = assertThrows(CsvFormatException.class, reader::read);

            assertEquals(3, error.lineNumber());
            assertTrue(error.getMessage().startsWith("Line 3: the text is not valid GBK/GB18030"), error.getMessage());
            assertInstanceOf(CharacterCodingException.class, error.getCause());
        }
    }

    private Path write(String name, byte[] content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.write(file, content);
        return file;
    }

    private static byte[] bytes(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static List<List<String>> readAll(Path file) throws IOException {
        List<List<String>> records = new ArrayList<>();
        try (CsvReader reader = CsvReader.open(file)) {
            CsvRecord record;
            while ((record = reader.read()) != null) {
                records.add(record.fields());
            }
        }
        return records;
    }
}
