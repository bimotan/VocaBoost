package com.vocabtrainer.service;

import com.vocabtrainer.domain.Exam;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.ReviewLogRepository;
import com.vocabtrainer.repository.TransactionRunner;
import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.service.scheduling.StudyDay;

import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Planning for an exam: the exam date of each deck (every deck's, or a deck's own), the countdown to
 * it and the new words a day it takes to start every new word before it. Saving an exam date also
 * brings forward the reviews already scheduled on or after it, so those cards get their last review
 * in the final days before the exam, as the scheduler does for every later review (see
 * {@link ReviewScheduler}). Days are study days.
 */
public class ExamPlanService {
    private static final Logger LOGGER = Logger.getLogger(ExamPlanService.class.getName());

    private final ExamSettings settings;
    private final WordRepository wordRepository;
    private final ReviewLogRepository reviewLogRepository;
    /** Null for the default new-cards-per-day limit in every deck. */
    private final ReviewSettings reviewSettings;
    private final ReviewScheduler scheduler;
    private final TransactionRunner transactions;
    private final Clock clock;

    public ExamPlanService(ExamSettings settings, WordRepository wordRepository, ReviewLogRepository reviewLogRepository,
                           ReviewSettings reviewSettings, ReviewScheduler scheduler, Clock clock) {
        this.settings = settings;
        this.wordRepository = wordRepository;
        this.reviewLogRepository = reviewLogRepository;
        this.reviewSettings = reviewSettings;
        this.scheduler = scheduler;
        this.transactions = wordRepository.transactions();
        this.clock = clock;
    }

    public ExamSettings settings() {
        return settings;
    }

    /** The exam the deck is studied for: its own, or else every deck's; empty when none is set. */
    public Optional<Exam> examFor(long deckId) {
        return settings.examFor(deckId);
    }

    /** How far away the deck's exam is; empty when it has none. */
    public Optional<ExamCountdown> countdown(long deckId) {
        LocalDate today = studyDay().of(LocalDateTime.now(clock));
        return examFor(deckId).map(exam -> new ExamCountdown(exam, ChronoUnit.DAYS.between(today, exam.date())));
    }

    /**
     * How many new words a day the deck needs to start every new word before its exam; empty when it
     * has no exam or the exam is today or over.
     */
    public Optional<NewCardPlan> newCardPlan(long deckId) {
        Optional<ExamCountdown> countdown = countdown(deckId);
        if (countdown.isEmpty() || countdown.get().daysLeft() < 1) {
            return Optional.empty();
        }
        try {
            StudyDay studyDay = studyDay();
            LocalDateTime now = LocalDateTime.now(clock);
            int introducedToday = reviewLogRepository.countNewCardsIntroducedSince(deckId, studyDay.start(studyDay.of(now)));
            return Optional.of(NewCardPlan.of(wordRepository.countNew(deckId), introducedToday,
                countdown.get().daysLeft(), newCardsPerDay(deckId)));
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot plan the new words of deck " + deckId, e);
        }
    }

    /**
     * Sets the deck's new-cards-per-day limit to what its {@link #newCardPlan} needs
     * ({@link NewCardPlan#limitToApply()}) and returns the plan.
     *
     * @throws IllegalStateException when the deck has no plan (no exam, or the exam is today or over)
     *                               or the limit cannot be saved
     */
    public NewCardPlan applyNewCardPlan(long deckId) {
        if (reviewSettings == null) {
            throw new IllegalStateException("The review settings are not saved, so the new-word limit cannot be set.");
        }
        NewCardPlan plan = newCardPlan(deckId)
            .orElseThrow(() -> new IllegalStateException("The deck has no exam ahead to plan new words for."));
        reviewSettings.saveNewCardsPerDay(deckId, plan.limitToApply());
        return plan;
    }

    /**
     * Saves the exam (null removes it) for every deck, which also drops the deck's own exam, or for
     * the deck only ({@code deckOnly}; null then makes it use every deck's again). Then the reviews
     * scheduled on or after an exam are brought forward; all in one transaction.
     *
     * @return how many cards were brought forward
     */
    public int saveExam(long deckId, boolean deckOnly, Exam exam) {
        try {
            return transactions.inTransaction(() -> {
                if (deckOnly) {
                    if (exam == null) {
                        settings.clearDeckExam(deckId);
                    } else {
                        settings.saveDeckExam(deckId, exam);
                    }
                } else {
                    if (exam == null) {
                        settings.clearDefaultExam();
                    } else {
                        settings.saveDefaultExam(exam);
                    }
                    settings.clearDeckExam(deckId);
                }
                return bringReviewsBeforeExams();
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot save the exam date", e);
        }
    }

    /**
     * Moves every review card that is due on or after its deck's exam, and was not reviewed in the
     * last days before it, to its last review day before the exam ({@link ReviewScheduler#dueBeforeExam}).
     */
    private int bringReviewsBeforeExams() throws SQLException {
        StudyDay studyDay = studyDay();
        LocalDateTime now = LocalDateTime.now(clock);
        List<WordCard> later = wordRepository.findReviewCardsDueFrom(studyDay.end(now));
        Map<Long, Optional<LocalDate>> examDates = new HashMap<>();
        int moved = 0;
        for (WordCard word : later) {
            Optional<LocalDate> examDate = examDates.computeIfAbsent(word.getDeckId(), settings::examDate);
            if (examDate.isEmpty()) {
                continue;
            }
            Optional<LocalDateTime> due = scheduler.dueBeforeExam(word, now, examDate.get());
            if (due.isPresent()) {
                word.setNextReviewAt(due.get());
                wordRepository.update(word);
                moved++;
            }
        }
        if (moved > 0) {
            LOGGER.info("Brought " + moved + " reviews forward to before the exam");
        }
        return moved;
    }

    private int newCardsPerDay(long deckId) {
        return reviewSettings == null ? ReviewSettings.DEFAULT_NEW_CARDS_PER_DAY : reviewSettings.newCardsPerDay(deckId);
    }

    private StudyDay studyDay() {
        return scheduler.studyDay();
    }
}
