package com.vocabtrainer.service;

import com.vocabtrainer.domain.DailyReviewStat;
import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.DailyGoalProgress;
import com.vocabtrainer.domain.HardWordStat;
import com.vocabtrainer.domain.MemoryBucketStat;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.domain.WorkloadDay;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.ReviewLogRepository;
import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.service.scheduling.StudyDay;
import com.vocabtrainer.util.DateTimeUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.function.Supplier;

import static com.vocabtrainer.util.Messages.tr;

public class StatsService {
    /** The memory distribution's bucket of words never reviewed. */
    public static final String NEW_WORDS_BUCKET = "New";

    private final WordRepository wordRepository;
    private final ReviewLogRepository reviewLogRepository;
    private final Clock clock;
    /** The scheduler's study day, which the user can change while the app runs. */
    private final Supplier<StudyDay> studyDays;
    /** The new-cards-per-day limits; null for the default limit in every deck. */
    private final ReviewSettings reviewSettings;

    public StatsService(WordRepository wordRepository, ReviewLogRepository reviewLogRepository) {
        this(wordRepository, reviewLogRepository, Clock.systemDefaultZone());
    }

    public StatsService(WordRepository wordRepository, ReviewLogRepository reviewLogRepository, Clock clock) {
        this(wordRepository, reviewLogRepository, clock, new StudyDay());
    }

    /** {@code studyDay} decides which words are due today. */
    public StatsService(WordRepository wordRepository, ReviewLogRepository reviewLogRepository, Clock clock,
                        StudyDay studyDay) {
        this(wordRepository, reviewLogRepository, clock, studyDay, null);
    }

    /** @param reviewSettings each deck's new-cards-per-day limit, which caps the new words counted as due today */
    public StatsService(WordRepository wordRepository, ReviewLogRepository reviewLogRepository, Clock clock,
                        StudyDay studyDay, ReviewSettings reviewSettings) {
        this(wordRepository, reviewLogRepository, clock, () -> studyDay, reviewSettings);
    }

    /**
     * @param studyDays      when a study day starts, read at every call: the scheduler's, so a changed
     *                       rollover hour changes what is due today at once
     * @param reviewSettings each deck's new-cards-per-day limit, which caps the new words counted as due today
     */
    public StatsService(WordRepository wordRepository, ReviewLogRepository reviewLogRepository, Clock clock,
                        Supplier<StudyDay> studyDays, ReviewSettings reviewSettings) {
        this.wordRepository = wordRepository;
        this.reviewLogRepository = reviewLogRepository;
        this.clock = clock;
        this.studyDays = studyDays;
        this.reviewSettings = reviewSettings;
    }

    /** @deprecated the queries moved into the repositories; use {@link #StatsService(WordRepository, ReviewLogRepository)}. */
    @Deprecated
    public StatsService(WordRepository wordRepository, ReviewLogRepository reviewLogRepository,
                        DatabaseManager databaseManager) {
        this(wordRepository, reviewLogRepository);
    }

    /** @deprecated the queries moved into the repositories; use {@link #StatsService(WordRepository, ReviewLogRepository, Clock)}. */
    @Deprecated
    public StatsService(WordRepository wordRepository, ReviewLogRepository reviewLogRepository,
                        DatabaseManager databaseManager, Clock clock) {
        this(wordRepository, reviewLogRepository, clock);
    }

    /** The deck's counts now; today's reviews and accuracy are those of the study day, see {@link DailyReviews}. */
    public DashboardStats dashboardStats(long deckId) {
        StudyDay studyDay = studyDays.get();
        try {
            LocalDateTime now = LocalDateTime.now(clock);
            ReviewLogRepository.DailyCount today = DailyReviews.on(reviewLogRepository, studyDay, deckId, studyDay.of(now));
            ReviewQueueCounts queue = queueCounts(studyDay, deckId, now);
            return new DashboardStats(
                wordRepository.countAll(deckId),
                queue.dueToday(),
                wordRepository.countMastered(deckId),
                today.reviews(),
                today.accuracy(),
                queue.dueReviews(),
                queue.newAvailableToday(),
                wordRepository.countSuspended(deckId)
            );
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read dashboard stats", e);
        }
    }

    public List<DailyReviewStat> dailyReviewStats(int days) {
        return dailyReviewStats(0, days);
    }

    /**
     * One entry per study day for the last {@code days} days, today last; a deckId of 0 or less
     * covers every deck. Practice is not counted, see {@link DailyReviews}.
     */
    public List<DailyReviewStat> dailyReviewStats(long deckId, int days) {
        StudyDay studyDay = studyDays.get();
        LocalDate end = studyDay.of(LocalDateTime.now(clock));
        LocalDate start = end.minusDays(Math.max(1, days) - 1L);
        try {
            return DailyReviews.between(reviewLogRepository, studyDay, deckId, start, end).stream()
                .map(count -> new DailyReviewStat(count.day(), count.reviews(), count.accuracy()))
                .toList();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read daily review stats", e);
        }
    }

    /**
     * The deck's workload for the next {@code days} study days, today first: the reviews already
     * scheduled on each day (today's with the overdue ones), read with one query grouped by study day,
     * and the new words the new-cards-per-day limit lets in each day until none are left (today what
     * is left of today's limit). Reviews that the new words and the coming reviews will add are not
     * forecast.
     */
    public List<WorkloadDay> workloadForecast(long deckId, int days) {
        int count = Math.max(1, days);
        StudyDay studyDay = studyDays.get();
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = studyDay.of(now);
        try {
            Map<LocalDate, Integer> due = wordRepository.countDueByStudyDay(deckId, today, studyDay.rolloverHour(),
                studyDay.start(today.plusDays(count)));
            ReviewQueueCounts queue = queueCounts(studyDay, deckId, now);
            int newWordsLeft = wordRepository.countNew(deckId);
            List<WorkloadDay> forecast = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                LocalDate day = today.plusDays(i);
                int newWords = Math.min(newWordsLeft, i == 0 ? queue.newAvailableToday() : queue.newCardsPerDay());
                newWordsLeft -= newWords;
                forecast.add(new WorkloadDay(day, due.getOrDefault(day, 0), newWords));
            }
            return forecast;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot forecast the workload", e);
        }
    }

    /**
     * How many of the deck's words fall into each range of recall chance at this moment (FSRS
     * retrievability), with words never reviewed counted under {@value #NEW_WORDS_BUCKET}.
     */
    public List<MemoryBucketStat> memoryDistribution(long deckId) {
        try {
            Map<String, Integer> buckets = new LinkedHashMap<>();
            buckets.put(NEW_WORDS_BUCKET, 0);
            buckets.put("0-40%", 0);
            buckets.put("40-70%", 0);
            buckets.put("70-90%", 0);
            buckets.put("90-100%", 0);
            LocalDateTime now = LocalDateTime.now(clock);
            for (WordCard word : wordRepository.findAll(deckId)) {
                OptionalDouble recall = ReviewScheduler.retrievability(word, now);
                if (recall.isEmpty()) {
                    buckets.merge(NEW_WORDS_BUCKET, 1, Integer::sum);
                    continue;
                }
                double strength = recall.getAsDouble();
                String bucket = strength < 0.4 ? "0-40%"
                    : strength < 0.7 ? "40-70%"
                    : strength < 0.9 ? "70-90%"
                    : "90-100%";
                buckets.compute(bucket, (key, value) -> value == null ? 1 : value + 1);
            }
            return buckets.entrySet().stream()
                .map(entry -> new MemoryBucketStat(entry.getKey(), entry.getValue()))
                .toList();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read memory distribution", e);
        }
    }

    public List<HardWordStat> hardestWords(long deckId, int limit) {
        try {
            return reviewLogRepository.hardestWords(deckId, limit);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read hardest words", e);
        }
    }

    /**
     * The deck's due reviews: learning and relearning cards whose step time has come and review cards
     * due today (see {@link WordCard#isDue}); new words are not counted.
     */
    public int overdueCount(long deckId) {
        try {
            return queueCounts(studyDays.get(), deckId, LocalDateTime.now(clock)).dueReviews();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read overdue count", e);
        }
    }

    private ReviewQueueCounts queueCounts(StudyDay studyDay, long deckId, LocalDateTime now) throws SQLException {
        return ReviewQueueCounts.read(wordRepository, reviewLogRepository, studyDay, deckId, now, newCardsPerDay(deckId));
    }

    private int newCardsPerDay(long deckId) {
        return reviewSettings == null ? ReviewSettings.DEFAULT_NEW_CARDS_PER_DAY : reviewSettings.newCardsPerDay(deckId);
    }

    /** When the deck was last reviewed, or null if never. */
    public LocalDateTime latestReviewAt(long deckId) {
        try {
            return reviewLogRepository.latestReviewAt(deckId).orElse(null);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read latest review time", e);
        }
    }

    /**
     * Word count, due count and latest review of each deck, read with one query per kind instead
     * of three queries per deck. The due count is the dashboard's "Due today": due reviews and the
     * new words the deck's new-cards-per-day limit still allows today.
     */
    public List<DeckOverview> deckOverviews(List<Deck> decks) {
        StudyDay studyDay = studyDays.get();
        try {
            LocalDateTime now = LocalDateTime.now(clock);
            Map<Long, WordRepository.DeckWordCounts> counts = wordRepository.countByDeck(now, studyDay.end(now));
            Map<Long, Integer> introducedToday =
                reviewLogRepository.newCardsIntroducedByDeckSince(studyDay.start(studyDay.of(now)));
            Map<Long, LocalDateTime> latestReviews = reviewLogRepository.latestReviewByDeck();
            WordRepository.DeckWordCounts none = new WordRepository.DeckWordCounts(0, 0, 0);
            return decks.stream()
                .map(deck -> {
                    WordRepository.DeckWordCounts deckCounts = counts.getOrDefault(deck.getId(), none);
                    ReviewQueueCounts queue = new ReviewQueueCounts(0, deckCounts.due() - deckCounts.dueNew(),
                        deckCounts.dueNew(), newCardsPerDay(deck.getId()), introducedToday.getOrDefault(deck.getId(), 0));
                    return new DeckOverview(deck, deckCounts.total(), queue.dueToday(), latestReviews.get(deck.getId()));
                })
                .toList();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read deck overview", e);
        }
    }

    public Path exportMarkdownReport(long deckId, Path outputPath) {
        return exportMarkdownReport(deckId, null, null, outputPath);
    }

    public Path exportMarkdownReport(long deckId, String deckName, DailyGoalProgress progress, Path outputPath) {
        try {
            String markdown = buildMarkdownReport(deckId, deckName, progress);
            Path parent = outputPath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(outputPath, markdown, StandardCharsets.UTF_8);
            return outputPath;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write report: " + outputPath, e);
        }
    }

    public String buildMarkdownReport(long deckId) {
        return buildMarkdownReport(deckId, null, null);
    }

    /**
     * The learning report, in the app's language: the deck's numbers, the goals (when {@code progress}
     * is given), the last 14 days, and the hardest words. A deck without a name is called by its id.
     */
    public String buildMarkdownReport(long deckId, String deckName, DailyGoalProgress progress) {
        DashboardStats dashboard = dashboardStats(deckId);
        String newline = System.lineSeparator();
        StringBuilder builder = new StringBuilder();
        builder.append("# ").append(tr("report.title")).append(newline).append(newline);
        item(builder, tr("report.deck", deckName == null || deckName.isBlank()
            ? tr("report.deckById", String.valueOf(deckId)) : deckName));
        item(builder, tr("report.totalWords", dashboard.totalWords()));
        if (dashboard.suspendedWords() > 0) {
            item(builder, tr("report.suspendedWords", dashboard.suspendedWords()));
        }
        item(builder, tr("report.dueWords", dashboard.dueToday()));
        item(builder, tr("report.masteredWords", dashboard.masteredWords()));
        item(builder, tr("report.reviewsToday", dashboard.reviewedToday()));
        item(builder, tr("report.accuracyToday", percent(dashboard.accuracyToday())));
        item(builder, tr("report.overdueWords", overdueCount(deckId)));
        builder.append(newline);
        if (progress != null) {
            heading(builder, tr("report.goals"));
            item(builder, tr("report.reviewGoal", progress.reviewedCount(), progress.reviewGoal()));
            item(builder, tr("report.newWordGoal", progress.newWordsCount(), progress.newWordGoal()));
            item(builder, tr("report.streak", DateTimeUtil.days(progress.currentStreak())));
            item(builder, tr("report.deckXp", progress.totalXp()));
            builder.append(newline);
        }
        heading(builder, tr("report.curve"));
        for (DailyReviewStat stat : dailyReviewStats(deckId, 14)) {
            item(builder, tr("report.curve.day", stat.date().toString(), stat.reviewCount(), percent(stat.accuracy())));
        }
        builder.append(newline);
        heading(builder, tr("report.analytics"));
        builder.append(tr("report.analytics.text")).append(newline).append(newline);
        heading(builder, tr("report.hardest"));
        for (HardWordStat word : hardestWords(deckId, 10)) {
            item(builder, tr("report.hardest.word", word.english(), percent(word.averageSimilarity()),
                tr("rating.again"), word.againCount()));
        }
        return builder.toString();
    }

    private static void heading(StringBuilder builder, String text) {
        builder.append("## ").append(text).append(System.lineSeparator()).append(System.lineSeparator());
    }

    private static void item(StringBuilder builder, String text) {
        builder.append("- ").append(text).append(System.lineSeparator());
    }

    private String percent(double value) {
        return String.format("%.0f%%", value * 100);
    }
}
