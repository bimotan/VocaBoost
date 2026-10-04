package com.vocabtrainer.service.csv;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.vocabtrainer.util.Messages.tr;

/**
 * The character encoding of a text file and the length of its byte order mark.
 *
 * <p>{@link #detect(Path)} trusts a UTF-8 or UTF-16 byte order mark. Without one, a file that is
 * valid UTF-8 from start to end is UTF-8; anything else is read as GB18030, a superset of the GBK
 * that Excel and Notepad on Chinese Windows save "CSV" and "ANSI" text in. The exception is a file
 * whose first {@value #UTF_8_EVIDENCE} or more non-ASCII characters are valid UTF-8 before a bad
 * byte: GBK text practically never gets that far as UTF-8, so the file is UTF-8 with a damaged or
 * pasted-in character, and reading it as UTF-8 reports that line instead of turning every Chinese
 * meaning into GB18030 mojibake.
 */
public record TextEncoding(Charset charset, int bomLength) {
    public static final Charset GB18030 = Charset.forName("GB18030");
    public static final TextEncoding UTF_8 = new TextEncoding(StandardCharsets.UTF_8, 0);

    /** Non-ASCII characters that must decode as UTF-8 before a bad byte for the file to count as UTF-8. */
    static final int UTF_8_EVIDENCE = 16;

    private static final int[] UTF_8_BOM = {0xEF, 0xBB, 0xBF};
    private static final int[] UTF_16LE_BOM = {0xFF, 0xFE};
    private static final int[] UTF_16BE_BOM = {0xFE, 0xFF};

    /** Reads the file once to find its encoding; the file is not kept in memory. */
    public static TextEncoding detect(Path path) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
            in.mark(UTF_8_BOM.length);
            byte[] head = in.readNBytes(UTF_8_BOM.length);
            if (startsWith(head, UTF_8_BOM)) {
                return new TextEncoding(StandardCharsets.UTF_8, UTF_8_BOM.length);
            }
            if (startsWith(head, UTF_16LE_BOM)) {
                return new TextEncoding(StandardCharsets.UTF_16LE, UTF_16LE_BOM.length);
            }
            if (startsWith(head, UTF_16BE_BOM)) {
                return new TextEncoding(StandardCharsets.UTF_16BE, UTF_16BE_BOM.length);
            }
            in.reset();
            return isUtf8(in) ? UTF_8 : new TextEncoding(GB18030, 0);
        }
    }

    /** A reader that skips the byte order mark and fails on bytes that are not valid in this encoding. */
    public Reader openReader(Path path) throws IOException {
        InputStream in = Files.newInputStream(path);
        try {
            in.skipNBytes(bomLength);
            return new StrictDecodingReader(in, charset);
        } catch (IOException | RuntimeException e) {
            in.close();
            throw e;
        }
    }

    /** A reader for a stream that is already positioned after any byte order mark. */
    public Reader openReader(InputStream in) {
        return new StrictDecodingReader(in, charset);
    }

    /** For messages: "UTF-8", "UTF-8 with BOM", "GBK/GB18030", ... */
    public String displayName() {
        String name = charset.equals(GB18030) ? "GBK/GB18030" : charset.name();
        return bomLength > 0 ? tr("csv.encoding.withBom", name) : name;
    }

    /**
     * The message for bytes that are not valid in this encoding; the advice differs because a UTF-8
     * file with a bad byte is damaged, while other files are usually just in an unexpected encoding.
     */
    public String undecodableMessage() {
        String advice = charset.equals(StandardCharsets.UTF_8) ? tr("csv.encoding.damaged") : tr("csv.encoding.saveAsUtf8");
        return tr("csv.encoding.invalid", displayName(), advice);
    }

    /** True when the text is valid UTF-8, or valid long enough to be UTF-8 with a bad byte. */
    private static boolean isUtf8(InputStream in) throws IOException {
        Reader reader = new StrictDecodingReader(in, StandardCharsets.UTF_8);
        char[] buffer = new char[8192];
        int nonAscii = 0;
        try {
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                for (int i = 0; i < count && nonAscii < UTF_8_EVIDENCE; i++) {
                    if (buffer[i] > 0x7F) {
                        nonAscii++;
                    }
                }
            }
            return true;
        } catch (CharacterCodingException e) {
            return nonAscii >= UTF_8_EVIDENCE;
        }
    }

    private static boolean startsWith(byte[] head, int[] bom) {
        if (head.length < bom.length) {
            return false;
        }
        for (int i = 0; i < bom.length; i++) {
            if ((head[i] & 0xFF) != bom[i]) {
                return false;
            }
        }
        return true;
    }
}
