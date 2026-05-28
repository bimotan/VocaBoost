package com.vocabtrainer.service;

import com.vocabtrainer.domain.ReviewLog;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.ReviewLogRepository;
import com.vocabtrainer.repository.WordRepository;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ReviewService {
    private final WordRepository wordRepository;
    private final ReviewLogRepository reviewLogRepository;
    private final SimilarityService similarityService;
    private final ReviewScheduler scheduler;
    private final Clock clock;
    private final Map<Long, ReviewAnswer> pendingAnswers = new HashMap<>();

    public ReviewService(WordRepository wordRepository, ReviewLogRepository reviewLogRepository,
                         SimilarityService similarityService, ReviewScheduler scheduler) {
        this(wordRepository, reviewLogRepository, similarityService, scheduler, Clock.systemDefaultZone());
    }

    public ReviewService(WordRepository wordRepository, ReviewLogRepository reviewLogRepository,
                         SimilarityService similarityService, ReviewScheduler scheduler, Clock clock) {
        this.wordRepository = wordRepository;
        this.reviewLogRepository = reviewLogRepository;
        this.similarityService = similarityService;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    public Optional<WordCard> nextDueWord(long deckId) {
        try {
            LocalDateTime now = LocalDateTime.now(clock);
            List<WordCard> dueWords = wordRepository.findDue(deckId, now, 50);
            return scheduler.selectNext(dueWords, now);
        } catch (SQLException e) {
            throw new IllegalStateException("读取待复习单词失败", e);
        }
    }

    public ReviewAnswer submitAnswer(long wordId, String userAnswer) {
        try {
            WordCard word = wordRepository.findById(wordId)
                .orElseThrow(() -> new IllegalArgumentException("单词不存在: " + wordId));
            double similarity = similarityService.calculate(userAnswer, word.getChinese());
            ReviewAnswer answer = new ReviewAnswer(
                wordId,
                word.getEnglish(),
                userAnswer == null ? "" : userAnswer.trim(),
                word.getChinese(),
                similarity,
                LocalDateTime.now(clock)
            );
            pendingAnswers.put(wordId, answer);
            return answer;
        } catch (SQLException e) {
            throw new IllegalStateException("提交答案失败", e);
        }
    }

    public WordCard rateCurrent(long wordId, ReviewRating rating) {
        try {
            WordCard word = wordRepository.findById(wordId)
                .orElseThrow(() -> new IllegalArgumentException("单词不存在: " + wordId));
            ReviewAnswer answer = pendingAnswers.remove(wordId);
            LocalDateTime now = LocalDateTime.now(clock);
            if (answer == null) {
                answer = new ReviewAnswer(wordId, word.getEnglish(), "", word.getChinese(), 0.0, now);
            }
            long elapsed = Math.max(0L, Duration.between(answer.submittedAt(), now).toMillis());
            scheduler.applyRating(word, rating, now);
            WordCard updated = wordRepository.save(word);
            reviewLogRepository.insert(new ReviewLog(
                0,
                wordId,
                now,
                answer.userAnswer(),
                answer.correctAnswer(),
                answer.similarity(),
                rating,
                elapsed
            ));
            return updated;
        } catch (SQLException e) {
            throw new IllegalStateException("保存复习结果失败", e);
        }
    }
}