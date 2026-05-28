package com.vocabtrainer.service;

import com.vocabtrainer.domain.ValidatedWord;

import java.util.regex.Pattern;

public class WordValidationService {
    private static final int MAX_ENGLISH_LENGTH = 80;
    private static final int MAX_CHINESE_LENGTH = 400;
    private static final Pattern ENGLISH_PATTERN = Pattern.compile("[A-Za-z][A-Za-z '\\-]*");
    private static final Pattern CHINESE_SEPARATOR_PATTERN = Pattern.compile("\\s*[;\\uFF1B,\\uFF0C/\\u3001]+\\s*");

    public ValidatedWord validate(String english, String chinese) {
        return validate(english, chinese, "", "", "", "", "");
    }

    public String validateEnglishOnly(String english) {
        String cleanEnglish = normalizeEnglish(english);
        validateEnglish(cleanEnglish);
        return cleanEnglish;
    }

    public ValidatedWord validate(String english, String chinese, String phonetic, String partOfSpeech,
                                  String exampleSentence, String note, String tags) {
        String cleanEnglish = normalizeEnglish(english);
        validateEnglish(cleanEnglish);

        String cleanChinese = normalizeChinese(chinese);
        if (cleanChinese.isBlank()) {
            throw new IllegalArgumentException("Chinese meaning cannot be empty.");
        }
        if (cleanChinese.length() > MAX_CHINESE_LENGTH) {
            throw new IllegalArgumentException("Chinese meaning is too long.");
        }

        return new ValidatedWord(
            cleanEnglish,
            cleanChinese,
            normalizeOptional(phonetic),
            normalizeOptional(partOfSpeech),
            normalizeOptional(exampleSentence),
            normalizeOptional(note),
            normalizeOptional(tags)
        );
    }

    public String normalizeEnglish(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("\\s+", " ");
    }

    public String normalizeChinese(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().replaceAll("\\s+", " ");
        normalized = CHINESE_SEPARATOR_PATTERN.matcher(normalized).replaceAll("; ");
        normalized = normalized.replaceAll("(;\\s*)+", "; ").trim();
        if (normalized.endsWith(";")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        return normalized;
    }

    private String normalizeOptional(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private void validateEnglish(String cleanEnglish) {
        if (cleanEnglish.isBlank()) {
            throw new IllegalArgumentException("English word cannot be empty.");
        }
        if (cleanEnglish.length() > MAX_ENGLISH_LENGTH) {
            throw new IllegalArgumentException("English word is too long.");
        }
        if (!ENGLISH_PATTERN.matcher(cleanEnglish).matches()) {
            throw new IllegalArgumentException("English can only contain letters, spaces, hyphens and apostrophes.");
        }
    }
}
