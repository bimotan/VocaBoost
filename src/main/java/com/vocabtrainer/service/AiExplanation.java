package com.vocabtrainer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * An AI explanation split into the sections the prompt asks for: the meaning, feedback on the
 * learner's answer, a memory tip or contrast with a confusable word, and an English example with its
 * Chinese translation. A reply that is not the requested JSON is kept as it is ({@link #raw()}).
 */
public record AiExplanation(String meaning, String answerFeedback, String memoryTip, String example,
                            String exampleTranslation, String raw) {
    private static final ObjectMapper LENIENT_JSON = JsonMapper.builder()
        .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
        .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
        .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
        .build();
    /** A reasoning model's thoughts, which some providers put in front of the answer. */
    private static final Pattern THINKING = Pattern.compile("(?is)<think>.*?</think>");
    private static final Pattern CODE_FENCE = Pattern.compile("(?s)^```[a-zA-Z]*\\s*(.*?)\\s*```$");
    /** Other names models use for the sections; keys are compared without case, spaces or underscores. */
    private static final Map<String, List<String>> ALIASES = Map.of(
        "meaning", List.of("meaning", "meaningzh", "definition", "释义", "词义"),
        "answer_feedback", List.of("answerfeedback", "feedback", "answer", "youranswer", "答案分析", "答案点评"),
        "memory_tip", List.of("memorytip", "mnemonic", "memory", "contrast", "tip", "记忆", "记忆提示"),
        "example_en", List.of("exampleen", "example", "examplesentence", "例句"),
        "example_zh", List.of("examplezh", "exampletranslation", "translation", "例句翻译", "翻译")
    );

    /**
     * Reads a reply: the JSON object the prompt asks for, also inside a code fence or after other
     * text, with lenient syntax; otherwise the text itself.
     */
    public static AiExplanation parse(String reply) {
        String text = reply == null ? "" : THINKING.matcher(reply).replaceAll("").strip();
        var fenced = CODE_FENCE.matcher(text);
        String unfenced = fenced.matches() ? fenced.group(1).strip() : text;
        int start = unfenced.indexOf('{');
        int end = unfenced.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                JsonNode root = LENIENT_JSON.readTree(unfenced.substring(start, end + 1));
                if (root != null && root.isObject()) {
                    AiExplanation structured = new AiExplanation(
                        field(root, "meaning"), field(root, "answer_feedback"), field(root, "memory_tip"),
                        exampleField(root, "en", "example_en"), exampleField(root, "zh", "example_zh"), text);
                    if (structured.isStructured()) {
                        return structured;
                    }
                }
            } catch (JsonProcessingException e) {
                // Not the requested JSON: show the reply as it is.
            }
        }
        return new AiExplanation("", "", "", "", "", text);
    }

    /** Whether the reply had at least one of the requested sections. */
    public boolean isStructured() {
        return !meaning.isEmpty() || !answerFeedback.isEmpty() || !memoryTip.isEmpty() || !example.isEmpty();
    }

    /** The sections, one per line, or the reply as it is when it had none. */
    public String text() {
        if (!isStructured()) {
            return raw;
        }
        String newline = System.lineSeparator();
        List<String> lines = new ArrayList<>();
        addSection(lines, "Meaning", meaning);
        addSection(lines, "About your answer", answerFeedback);
        addSection(lines, "Memory tip", memoryTip);
        if (!example.isEmpty()) {
            lines.add("Example: " + example + (exampleTranslation.isEmpty() ? "" : newline + "  " + exampleTranslation));
        } else if (!exampleTranslation.isEmpty()) {
            lines.add("Example: " + exampleTranslation);
        }
        return String.join(newline, lines);
    }

    private static void addSection(List<String> lines, String label, String value) {
        if (!value.isEmpty()) {
            lines.add(label + ": " + value);
        }
    }

    private static String field(JsonNode root, String key) {
        JsonNode value = find(root, key);
        return value == null ? "" : text(value);
    }

    /** The example also comes as {"example": {"en": ..., "zh": ...}}. */
    private static String exampleField(JsonNode root, String language, String key) {
        JsonNode example = find(root, "example_en");
        if (example != null && example.isObject()) {
            JsonNode part = example.get(language);
            return part == null ? "" : text(part);
        }
        return field(root, key);
    }

    private static JsonNode find(JsonNode root, String key) {
        List<String> names = ALIASES.get(key);
        for (String name : names) {
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (normalize(field.getKey()).equals(name)) {
                    return field.getValue();
                }
            }
        }
        return null;
    }

    private static String normalize(String key) {
        return key.toLowerCase(Locale.ROOT).replaceAll("[\\s_-]", "");
    }

    /**
     * A value's text: a string or a number, or the texts in an array or object (such as
     * {"zh": "清晰的", "pos": "adj."}) joined with "; ".
     */
    private static String text(JsonNode value) {
        if (value.isContainerNode()) {
            List<String> parts = new ArrayList<>();
            value.forEach(element -> {
                String part = text(element);
                if (!part.isEmpty()) {
                    parts.add(part);
                }
            });
            return String.join("; ", parts);
        }
        return value.isValueNode() && !value.isNull() ? value.asText("").strip() : "";
    }
}
