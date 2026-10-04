package com.vocabtrainer.domain;

import java.time.LocalDate;
import java.util.Objects;

import static com.vocabtrainer.util.Messages.tr;

/**
 * The exam a deck is studied for, such as the GRE on 2026-11-16. Reviews that would fall on or after
 * its date are brought forward into the last days before it.
 *
 * @param name what the Dashboard calls it ("GRE in 45 days"); at most {@value #MAX_NAME_LENGTH} characters
 * @param date the day of the exam, a study day
 */
public record Exam(String name, LocalDate date) {
    public static final String DEFAULT_NAME = "GRE";
    public static final int MAX_NAME_LENGTH = 30;

    public Exam {
        Objects.requireNonNull(date, "date");
        name = name == null || name.isBlank() ? DEFAULT_NAME : name.trim().replaceAll("\\s+", " ");
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(tr("validation.examName", MAX_NAME_LENGTH));
        }
    }
}
