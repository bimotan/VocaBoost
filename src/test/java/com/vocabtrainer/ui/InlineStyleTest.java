package com.vocabtrainer.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No view sets an inline style: colours and font sizes live in app.css, where the text size setting
 * scales them and {@link AppStyleContrastTest} checks their contrast.
 */
class InlineStyleTest {
    private static final Path SOURCES = Path.of("src", "main", "java");

    @Test
    void noViewSetsAnInlineStyle() throws IOException {
        List<String> inline = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).contains(".setStyle(")) {
                        inline.add(SOURCES.relativize(file) + ":" + (i + 1));
                    }
                }
            }
        }
        assertTrue(inline.isEmpty(), "inline styles (use a style class in app.css): " + inline);
    }
}
