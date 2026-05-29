package com.vocabtrainer.service;

import com.vocabtrainer.domain.DailyReviewStat;
import com.vocabtrainer.domain.HardWordStat;
import com.vocabtrainer.domain.MemoryBucketStat;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.ReviewLogRepository;
import com.vocabtrainer.repository.WordRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class StatsService {
    private final WordRepository wordRepository;
    private final ReviewLogRepository reviewLogRepository;
    private final DatabaseManager databaseManager;
    private final Clock clock;

    public StatsService(WordRepository wordRepository, ReviewLogRepository reviewLogRepository) {
        this(wordRepository, reviewLogRepository, null, Clock.systemDefaultZone());
    }

    public StatsService(WordRepository wordRepository, ReviewLogRepository reviewLogRepository,
                        DatabaseManager databaseManager) {
        this(wordRepository, reviewLogRepository, databaseManager, Clock.systemDefaultZone());
    }

    public StatsService(WordRepository wordRepository, ReviewLogRepository reviewLogRepository,
                        DatabaseManager databaseManager, Clock clock) {
        this.wordRepository = wordRepository;
        this.reviewLogRepository = reviewLogRepository;
        this.databaseManager = databaseManager;
        this.clock = clock;
    }

    public DashboardStats dashboardStats(long deckId) {
        try {
            LocalDateTime now = LocalDateTime.now(clock);
            LocalDateTime startOfDay = LocalDate.now(clock).atStartOfDay();
            int reviewedToday = reviewLogRepository.countSince(startOfDay);
            int correctToday = reviewLogRepository.countCorrectSince(startOfDay);
            double accuracy = reviewedToday == 0 ? 0.0 : (double) correctToday / reviewedToday;
            return new DashboardStats(
                wordRepository.countAll(deckId),
                wordRepository.countDue(deckId, now),
                wordRepository.countMastered(deckId),
                reviewedToday,
                accuracy
            );
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read dashboard stats", e);
        }
    }

    public List<DailyReviewStat> dailyReviewStats(int days) {
        return dailyReviewStats(0, days);
    }

    public List<DailyReviewStat> dailyReviewStats(long deckId, int days) {
        if (databaseManager == null) {
            return List.of();
        }
        LocalDate end = LocalDate.now(clock);
        LocalDate start = end.minusDays(Math.max(1, days) - 1L);
        Map<LocalDate, MutableDailyStat> map = new LinkedHashMap<>();
        for (int i = 0; i < days; i++) {
            map.put(start.plusDays(i), new MutableDailyStat());
        }
        String sql = deckId <= 0 ? """
            SELECT substr(reviewed_at, 1, 10) AS day,
                   COUNT(*) AS reviews,
                   SUM(CASE WHEN rating <> 'AGAIN' THEN 1 ELSE 0 END) AS correct
            FROM review_logs
            WHERE reviewed_at >= ?
            GROUP BY substr(reviewed_at, 1, 10)
            ORDER BY day
            """ : """
            SELECT substr(l.reviewed_at, 1, 10) AS day,
                   COUNT(*) AS reviews,
                   SUM(CASE WHEN l.rating <> 'AGAIN' THEN 1 ELSE 0 END) AS correct
            FROM review_logs l
            JOIN words w ON w.id = l.word_id
            WHERE w.deck_id = ? AND l.reviewed_at >= ?
            GROUP BY substr(l.reviewed_at, 1, 10)
            ORDER BY day
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (deckId <= 0) {
                statement.setString(1, start.atStartOfDay().toString());
            } else {
                statement.setLong(1, deckId);
                statement.setString(2, start.atStartOfDay().toString());
            }
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    LocalDate day = LocalDate.parse(rs.getString("day"));
                    MutableDailyStat stat = map.get(day);
                    if (stat != null) {
                        stat.reviews = rs.getInt("reviews");
                        stat.correct = rs.getInt("correct");
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read daily review stats", e);
        }
        return map.entrySet().stream()
            .map(entry -> new DailyReviewStat(
                entry.getKey(),
                entry.getValue().reviews,
                entry.getValue().reviews == 0 ? 0.0 : entry.getValue().correct / (double) entry.getValue().reviews
            ))
            .toList();
    }

    public List<MemoryBucketStat> memoryDistribution(long deckId) {
        try {
            Map<String, Integer> buckets = new LinkedHashMap<>();
            buckets.put("0-40%", 0);
            buckets.put("40-70%", 0);
            buckets.put("70-90%", 0);
            buckets.put("90-100%", 0);
            LocalDateTime now = LocalDateTime.now(clock);
            for (WordCard word : wordRepository.findAll(deckId)) {
                double strength = word.calculateMemoryStrength(now);
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
        if (databaseManager == null) {
            return List.of();
        }
        String sql = """
            SELECT w.english, w.chinese, COUNT(l.id) AS reviews,
                   AVG(l.similarity) AS avg_similarity,
                   SUM(CASE WHEN l.rating = 'AGAIN' THEN 1 ELSE 0 END) AS again_count
            FROM words w
            JOIN review_logs l ON l.word_id = w.id
            WHERE w.deck_id = ? AND w.archived = 0
            GROUP BY w.id
            ORDER BY avg_similarity ASC, again_count DESC, reviews DESC
            LIMIT ?
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, deckId);
            statement.setInt(2, limit);
            try (ResultSet rs = statement.executeQuery()) {
                List<HardWordStat> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(new HardWordStat(
                        rs.getString("english"),
                        rs.getString("chinese"),
                        rs.getInt("reviews"),
                        rs.getDouble("avg_similarity"),
                        rs.getInt("again_count")
                    ));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read hardest words", e);
        }
    }

    public int overdueCount(long deckId) {
        try {
            return wordRepository.countDue(deckId, LocalDateTime.now(clock));
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read overdue count", e);
        }
    }

    public Path exportMarkdownReport(long deckId, Path outputPath) {
        try {
            String markdown = buildMarkdownReport(deckId);
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
        DashboardStats dashboard = dashboardStats(deckId);
        StringBuilder builder = new StringBuilder();
        builder.append("# VocaBoost Learning Report").append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("- Total words: ").append(dashboard.totalWords()).append(System.lineSeparator());
        builder.append("- Due words: ").append(dashboard.dueToday()).append(System.lineSeparator());
        builder.append("- Mastered words: ").append(dashboard.masteredWords()).append(System.lineSeparator());
        builder.append("- Reviews today: ").append(dashboard.reviewedToday()).append(System.lineSeparator());
        builder.append("- Accuracy today: ").append(percent(dashboard.accuracyToday())).append(System.lineSeparator());
        builder.append("- Overdue words: ").append(overdueCount(deckId)).append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("## Learning Analytics").append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("VocaBoost combines spaced repetition, retrieval practice, adaptive scheduling, ")
            .append("and review-log analytics to prioritize words that are due, weak, or repeatedly vague.")
            .append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("## Hardest Words").append(System.lineSeparator()).append(System.lineSeparator());
        for (HardWordStat word : hardestWords(deckId, 10)) {
            builder.append("- ").append(word.english())
                .append(": avg similarity ").append(percent(word.averageSimilarity()))
                .append(", Again ").append(word.againCount()).append(System.lineSeparator());
        }
        return builder.toString();
    }

    private String percent(double value) {
        return String.format("%.0f%%", value * 100);
    }

    private static class MutableDailyStat {
        int reviews;
        int correct;
    }
}
