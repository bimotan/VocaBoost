package com.vocabtrainer.util;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static com.vocabtrainer.util.Messages.tr;

public final class DateTimeUtil {
    public static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    public static final DateTimeFormatter LEGACY_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DISPLAY_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private DateTimeUtil() {
    }

    public static String toDatabase(LocalDateTime value) {
        return value == null ? null : value.format(ISO_FORMATTER);
    }

    public static LocalDateTime fromDatabase(String value) {
        return value == null || value.isBlank() ? null : LocalDateTime.parse(value, ISO_FORMATTER);
    }

    /** Days, such as daily_goals.goal_date, are stored as ISO dates (yyyy-MM-dd), which sort by date. */
    public static String toDatabaseDate(LocalDate value) {
        return value == null ? null : value.format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    public static LocalDate dateFromDatabase(String value) {
        return value == null || value.isBlank() ? null : LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
    }

    /** A number of days as text: "1 day", "0 days", "12 days". */
    public static String days(long count) {
        return tr("format.days", count);
    }

    public static String toDisplay(LocalDateTime value) {
        return value == null ? "-" : value.format(DISPLAY_FORMATTER);
    }

    public static Path defaultDatabasePath() {
        return Path.of(System.getProperty("user.home"), ".vocab-trainer", "vocab.db");
    }
}