package com.vocabtrainer.service.csv;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The word fields a word-list CSV can hold, with the header names each is recognized by. Header
 * names are compared ignoring case, spaces, '_', '-' and '.', so "Part of speech" and
 * "part_of_speech" are the same name. The first alias is the name "Export words CSV" writes; when a
 * header has several aliases of one field, the earlier alias wins (ECDICT has both "translation",
 * the Chinese, and "definition", the English definition).
 */
public enum WordColumn {
    ENGLISH("english", "word", "words", "term", "headword", "vocabulary", "vocab",
        "单词", "英文", "英语", "英文单词", "英语单词", "词汇", "词语"),
    CHINESE("chinese", "translation", "meaning", "meanings", "chinese meaning",
        "释义", "中文", "中文释义", "中文意思", "中文翻译", "中文含义", "意思", "词义", "翻译", "解释", "含义", "definition"),
    PHONETIC("phonetic", "phonetics", "pronunciation", "ipa", "音标", "发音"),
    POS("pos", "part of speech", "word class", "词性"),
    EXAMPLE("example", "examples", "example sentence", "sentence", "例句"),
    NOTE("note", "notes", "memo", "comment", "comments", "备注", "笔记", "注释"),
    TAGS("tags", "tag", "labels", "label", "标签");

    private static final Map<String, Alias> ALIASES = new HashMap<>();

    static {
        for (WordColumn column : values()) {
            for (int rank = 0; rank < column.aliases.size(); rank++) {
                if (ALIASES.put(normalize(column.aliases.get(rank)), new Alias(column, rank)) != null) {
                    throw new IllegalStateException("Header alias used twice: " + column.aliases.get(rank));
                }
            }
        }
    }

    private final List<String> aliases;

    WordColumn(String... aliases) {
        this.aliases = List.of(aliases);
    }

    /** The name "Export words CSV" writes in its header row. */
    public String headerName() {
        return aliases.get(0);
    }

    public List<String> aliases() {
        return aliases;
    }

    /** The field a header cell names, with the alias's rank (0 = preferred); null when it names none. */
    static Alias match(String headerCell) {
        return ALIASES.get(normalize(headerCell));
    }

    /** A header name as it is compared: lower case, without spaces, '_', '-', '.' or a byte order mark. */
    public static String normalize(String name) {
        StringBuilder builder = new StringBuilder();
        for (char c : name.toLowerCase(Locale.ROOT).toCharArray()) {
            if (!Character.isWhitespace(c) && c != '_' && c != '-' && c != '.' && c != '\uFEFF') {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    record Alias(WordColumn column, int rank) {
    }
}
