package com.vocabtrainer.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The text colours of app.css are readable: a contrast of at least 4.5:1, the WCAG AA level for
 * normal text, on every background they are used on.
 */
class AppStyleContrastTest {
    /** Modena's window background, which app.css does not change. */
    private static final String WINDOW_BACKGROUND = "#f4f4f4";
    private static final String FIELD_BACKGROUND = "#ffffff";
    private static final List<String> TEXT_COLOURS =
        List.of("-vb-text-secondary", "-vb-text-muted", "-vb-text-hint", "-vb-accent", "-vb-error", "-vb-warning-text");
    private static final List<String> CARD_BACKGROUNDS =
        List.of("-vb-surface", "-vb-success-surface", "-vb-warning-surface");

    @Test
    void everyTextColourHasAContrastOfAtLeast4point5OnEveryBackground() throws IOException {
        Map<String, String> colours = rootColours();
        Map<String, String> backgrounds = new LinkedHashMap<>();
        backgrounds.put("window", WINDOW_BACKGROUND);
        backgrounds.put("field", FIELD_BACKGROUND);
        CARD_BACKGROUNDS.forEach(name -> backgrounds.put(name, colours.get(name)));
        for (String text : TEXT_COLOURS) {
            for (Map.Entry<String, String> background : backgrounds.entrySet()) {
                double ratio = contrast(colours.get(text), background.getValue());
                assertTrue(ratio >= 4.5, text + " " + colours.get(text) + " on " + background.getKey() + " "
                    + background.getValue() + ": " + String.format("%.2f", ratio));
            }
        }
    }

    @Test
    void theContrastFormulaMatchesKnownValues() {
        assertEquals(21.0, contrast("#000000", "#ffffff"), 0.01);
        assertEquals(1.0, contrast("#777777", "#777777"), 0.001);
        // The muted grey the app used before: readable on white, not on the window background.
        assertEquals(4.83, contrast("#6b7280", "#ffffff"), 0.01);
        assertEquals(4.40, contrast("#6b7280", WINDOW_BACKGROUND), 0.01);
    }

    /** The looked-up colours app.css defines on .root, by name. */
    private static Map<String, String> rootColours() throws IOException {
        String css;
        try (InputStream in = AppStyle.class.getResourceAsStream("app.css")) {
            css = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher root = Pattern.compile("(?m)^\\.root \\{([^}]*)}").matcher(css);
        assertTrue(root.find(), "app.css has a .root block");
        Map<String, String> colours = new LinkedHashMap<>();
        Matcher colour = Pattern.compile("(-vb-[a-z-]+):\\s*(#[0-9a-fA-F]{6});").matcher(root.group(1));
        while (colour.find()) {
            colours.put(colour.group(1), colour.group(2));
        }
        for (String name : TEXT_COLOURS) {
            assertTrue(colours.containsKey(name), name + " in " + colours);
        }
        return colours;
    }

    /** The WCAG 2 contrast ratio of two sRGB colours, from 1 to 21. */
    static double contrast(String first, String second) {
        double lighter = Math.max(luminance(first), luminance(second));
        double darker = Math.min(luminance(first), luminance(second));
        return (lighter + 0.05) / (darker + 0.05);
    }

    private static double luminance(String hex) {
        int rgb = Integer.parseInt(hex.substring(1), 16);
        return 0.2126 * channel(rgb >> 16) + 0.7152 * channel(rgb >> 8) + 0.0722 * channel(rgb);
    }

    private static double channel(int value) {
        double c = (value & 0xff) / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
