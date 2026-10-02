package com.vocabtrainer.service.wordlist;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the HTML of an Anki field into the plain text a word field holds, and back for the Anki
 * export. Line breaks ({@code <br>}, {@code <div>}, {@code <p>}, {@code <li>}) become "; " (a space
 * after a line that ends in punctuation), style sheets and scripts are dropped with their text, other
 * tags are dropped and entities are unescaped. Anki's {@code [sound:...]} references and {@code <img>}
 * tags are dropped from every field, HTML or not, since a card has no use for them here, and Anki's
 * cloze deletions ({@code {{c1::abate}}}, also with a hint: {@code {{c1::abate::reduce}}}) keep
 * only their text, so an example sentence reads as a sentence and the Cloze review can blank the word.
 */
public final class HtmlText {
    private static final Pattern SOUND = Pattern.compile("\\[sound:[^\\]]*\\]");
    /** An Anki cloze deletion: its text, then an optional "::hint", which is dropped. */
    private static final Pattern CLOZE_DELETION = Pattern.compile("\\{\\{c\\d+::(.*?)(?:::(?:(?!\\}\\}).)*)?\\}\\}",
        Pattern.DOTALL);
    private static final Pattern IMAGE = Pattern.compile("<img\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    /** Style sheets and scripts pasted into a field: their text is not part of the field's text. */
    private static final Pattern STYLE_OR_SCRIPT = Pattern.compile("<(style|script)\\b[^>]*>.*?</\\1\\s*>",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern BREAK = Pattern.compile("<\\s*(?:br|/?div|/?p|/?li|hr)\\b[^>]*>",
        Pattern.CASE_INSENSITIVE);
    /** A tag or comment; "a < b" and "<3" are text. */
    private static final Pattern TAG = Pattern.compile("<!--.*?-->|</?[A-Za-z][^>]*>", Pattern.DOTALL);
    private static final Pattern ENTITY = Pattern.compile("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[A-Za-z][A-Za-z0-9]{1,31});");
    /** What makes a field of a file without "#html:" read as HTML. */
    private static final Pattern LOOKS_LIKE_HTML = Pattern.compile(
        "</?(?:br|div|p|span|b|i|u|em|strong|font|img|sup|sub|ul|ol|li|a|hr|style|script)\\b[^>]*>|&(?:nbsp|amp|lt|gt|quot|#[0-9]+);",
        Pattern.CASE_INSENSITIVE);
    private static final String ENDS_CLAUSE = ",.;:!?，。；：！？、";
    private static final Map<String, String> NAMED_ENTITIES = Map.ofEntries(
        Map.entry("amp", "&"), Map.entry("lt", "<"), Map.entry("gt", ">"), Map.entry("quot", "\""),
        Map.entry("apos", "'"), Map.entry("nbsp", " "), Map.entry("ensp", " "), Map.entry("emsp", " "),
        Map.entry("thinsp", " "), Map.entry("hellip", "…"), Map.entry("mdash", "—"), Map.entry("ndash", "–"),
        Map.entry("lsquo", "‘"), Map.entry("rsquo", "’"), Map.entry("ldquo", "“"), Map.entry("rdquo", "”"),
        Map.entry("middot", "·"), Map.entry("times", "×"), Map.entry("deg", "°"), Map.entry("copy", "©"),
        Map.entry("eacute", "é"), Map.entry("egrave", "è"), Map.entry("aacute", "á"), Map.entry("iuml", "ï"),
        Map.entry("ouml", "ö"), Map.entry("uuml", "ü"), Map.entry("ccedil", "ç"));

    private HtmlText() {
    }

    /** True when {@code value} contains an HTML tag or entity that Anki fields use. */
    public static boolean looksLikeHtml(String value) {
        return value != null && (value.indexOf('<') >= 0 || value.indexOf('&') >= 0)
            && LOOKS_LIKE_HTML.matcher(value).find();
    }

    /**
     * The text of a field: without sound and image references, with cloze deletions as their text
     * and, when {@code html} is true, with
     * line breaks as "; ", without tags and with entities unescaped.
     */
    public static String toText(String value, boolean html) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String text = SOUND.matcher(value).replaceAll("");
        text = CLOZE_DELETION.matcher(text).replaceAll("$1");
        text = IMAGE.matcher(text).replaceAll("");
        if (!html) {
            return text.strip();
        }
        text = STYLE_OR_SCRIPT.matcher(text).replaceAll("");
        text = BREAK.matcher(text).replaceAll("\n");
        text = TAG.matcher(text).replaceAll("");
        text = unescape(text).replace(' ', ' ');
        StringBuilder result = new StringBuilder();
        for (String line : text.split("\\R")) {
            String clean = line.strip();
            if (clean.isEmpty()) {
                continue;
            }
            if (result.length() > 0) {
                // A line that already ends a clause ("He refused,<br>again") goes on after a space.
                result.append(ENDS_CLAUSE.indexOf(result.charAt(result.length() - 1)) >= 0 ? " " : "; ");
            }
            result.append(clean);
        }
        return result.toString();
    }

    /** Unescapes named entities that Anki and editors write, and every numeric one; others stay as they are. */
    public static String unescape(String text) {
        if (text.indexOf('&') < 0) {
            return text;
        }
        Matcher matcher = ENTITY.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String replacement = null;
            if (name.charAt(0) == '#') {
                try {
                    int code = name.charAt(1) == 'x' || name.charAt(1) == 'X'
                        ? Integer.parseInt(name.substring(2), 16)
                        : Integer.parseInt(name.substring(1));
                    if (Character.isValidCodePoint(code) && code != 0) {
                        replacement = new String(Character.toChars(code));
                    }
                } catch (NumberFormatException e) {
                    // Too large: left as it is.
                }
            } else {
                replacement = NAMED_ENTITIES.get(name);
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement == null ? matcher.group() : replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /** {@code text} as HTML: {@code & < > "} escaped. */
    public static String escape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder html = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> html.append("&amp;");
                case '<' -> html.append("&lt;");
                case '>' -> html.append("&gt;");
                case '"' -> html.append("&quot;");
                default -> html.append(c);
            }
        }
        return html.toString();
    }
}
