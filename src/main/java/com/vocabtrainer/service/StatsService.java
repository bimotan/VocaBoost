package com.vocabtrainer.service;

import com.vocabtrainer.repository.ReviewLogRepository;
import com.vocabtrainer.repository.WordRepository;

import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

public class StatsService {
    private final WordRepository wordRepository;
    private final ReviewLogRepository reviewLogRepository;
    private final Clock clock;

    public StatsService(WordRepository wordRepository, ReviewLogRepository reviewLogRepository) {
        this(wordRepository, reviewLogRepository, Clock.systemDefaultZone());
    }

    public StatsService(WordRepository wordRepository, ReviewLogRepository reviewLogRepository, Clock clock) {
        this.wordRepository = wordRepository;
        this.reviewLogRepository = reviewLogRepository;
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
            throw new IllegalStateException("读取统计数据失败", e);
        }
    }
}