package com.vocabtrainer.service;

import com.vocabtrainer.domain.Exam;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * The exam dates the user set, kept in the {@code settings} table: the exam of every deck
 * ({@code exam.date}, {@code exam.name}) and the exam of a deck that has its own
 * ({@code exam.date.<deckId>}, {@code exam.name.<deckId>}). Dates are ISO dates (2026-11-16). A
 * saved date that is not valid is logged and ignored.
 */
public class ExamSettings {
    static final String DATE_KEY = "exam.date";
    static final String NAME_KEY = "exam.name";

    private static final Logger LOGGER = Logger.getLogger(ExamSettings.class.getName());

    private final SettingsService settings;

    public ExamSettings(SettingsService settings) {
        this.settings = settings;
    }

    /** The exam every deck without its own is studied for; empty when none is set. */
    public Optional<Exam> defaultExam() {
        return read(DATE_KEY, NAME_KEY);
    }

    /** The exam the deck has of its own; empty when it uses the default. */
    public Optional<Exam> deckExam(long deckId) {
        return read(DATE_KEY + "." + deckId, NAME_KEY + "." + deckId);
    }

    /** The exam the deck is studied for: its own, or else the default; empty when there is none. */
    public Optional<Exam> examFor(long deckId) {
        return deckExam(deckId).or(this::defaultExam);
    }

    /** The date of {@link #examFor}; the scheduler keeps the deck's reviews before it. */
    public Optional<LocalDate> examDate(long deckId) {
        return examFor(deckId).map(Exam::date);
    }

    /**
     * The first exam day after {@code day} of every deck's exam and the decks' own; empty when no
     * exam is set after it.
     */
    public Optional<LocalDate> firstExamAfter(LocalDate day) {
        return settings.getByPrefix(DATE_KEY).values().stream()
            .map(ExamSettings::parseDate)
            .flatMap(Optional::stream)
            .filter(date -> date.isAfter(day))
            .min(LocalDate::compareTo);
    }

    public void saveDefaultExam(Exam exam) {
        write(DATE_KEY, NAME_KEY, exam);
    }

    public void clearDefaultExam() {
        settings.delete(DATE_KEY);
        settings.delete(NAME_KEY);
    }

    public void saveDeckExam(long deckId, Exam exam) {
        write(DATE_KEY + "." + deckId, NAME_KEY + "." + deckId, exam);
    }

    /** The deck goes back to the default exam. */
    public void clearDeckExam(long deckId) {
        settings.delete(DATE_KEY + "." + deckId);
        settings.delete(NAME_KEY + "." + deckId);
    }

    private Optional<Exam> read(String dateKey, String nameKey) {
        Optional<String> date = settings.get(dateKey).filter(value -> !value.isBlank());
        if (date.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Exam(settings.get(nameKey).orElse(""), LocalDate.parse(date.get().trim())));
        } catch (DateTimeParseException | IllegalArgumentException e) {
            LOGGER.warning("Ignoring exam setting " + dateKey + "=" + date.get() + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    private static Optional<LocalDate> parseDate(String value) {
        try {
            return value == null || value.isBlank() ? Optional.empty() : Optional.of(LocalDate.parse(value.trim()));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private void write(String dateKey, String nameKey, Exam exam) {
        settings.save(dateKey, exam.date().toString());
        settings.save(nameKey, exam.name());
    }
}
