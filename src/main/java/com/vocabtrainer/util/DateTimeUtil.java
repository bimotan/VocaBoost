package com.vocabtrainer.util;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class DateTimeUtil {
    public static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    public static final DateTimeFormatter LEGACY_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private DateTimeUtil() {
    }

    public static String toDatabase(LocalDateTime value) {
        return value == null ? null : value.format(ISO_FORMATTER);
    }

    public static LocalDateTime fromDatabase(String value) {
        return value == null || value.isBlank() ? null : LocalDateTime.parse(value, ISO_FORMATTER);
    }

    public static String toDisplay(LocalDateTime value) {
        return value == null ? "-" : value.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    public static Path defaultDatabasePath() {
        return Path.of(System.getProperty("user.home"), ".vocab-trainer", "vocab.db");
    }
}