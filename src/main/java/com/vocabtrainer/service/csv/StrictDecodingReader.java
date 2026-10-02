package com.vocabtrainer.service.csv;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;

/**
 * Decodes bytes and fails on bytes that are not valid in the charset, instead of replacing them.
 *
 * <p>Unlike {@link java.io.InputStreamReader}, it first hands out every character decoded before the
 * bad bytes and throws only on the next read, so a reader counting lines knows exactly where the
 * problem is.
 */
final class StrictDecodingReader extends Reader {
    private final InputStream in;
    private final CharsetDecoder decoder;
    private final ByteBuffer bytes = ByteBuffer.allocate(16 * 1024).flip();
    private boolean endOfInput;
    private boolean flushed;
    private CharacterCodingException error;

    StrictDecodingReader(InputStream in, Charset charset) {
        this.in = in;
        this.decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    }

    @Override
    public int read(char[] target, int offset, int length) throws IOException {
        if (error != null) {
            throw error;
        }
        if (length == 0) {
            return 0;
        }
        CharBuffer out = CharBuffer.wrap(target, offset, length);
        while (true) {
            if (flushed) {
                return produced(out, offset);
            }
            CoderResult result = decoder.decode(bytes, out, endOfInput);
            if (result.isError()) {
                try {
                    result.throwException();
                } catch (CharacterCodingException e) {
                    error = e;
                }
                if (out.position() > offset) {
                    return out.position() - offset;
                }
                throw error;
            }
            if (result.isOverflow()) {
                return out.position() - offset;
            }
            if (endOfInput) {
                if (decoder.flush(out).isOverflow()) {
                    return out.position() - offset;
                }
                flushed = true;
                return produced(out, offset);
            }
            if (out.position() > offset) {
                // Hand out what is decoded before blocking on more input.
                return out.position() - offset;
            }
            readMoreBytes();
        }
    }

    private void readMoreBytes() throws IOException {
        bytes.compact();
        int count = in.read(bytes.array(), bytes.arrayOffset() + bytes.position(), bytes.remaining());
        if (count < 0) {
            endOfInput = true;
        } else {
            bytes.position(bytes.position() + count);
        }
        bytes.flip();
    }

    private static int produced(CharBuffer out, int offset) {
        int count = out.position() - offset;
        return count > 0 ? count : -1;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
