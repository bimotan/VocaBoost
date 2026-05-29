package com.vocabtrainer.service;

import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.AiCacheRepository;

import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;

public class CachingAiService implements AiService {
    private final AiService delegate;
    private final AiCacheRepository cacheRepository;
    private final Clock clock;

    public CachingAiService(AiService delegate, AiCacheRepository cacheRepository) {
        this(delegate, cacheRepository, Clock.systemDefaultZone());
    }

    public CachingAiService(AiService delegate, AiCacheRepository cacheRepository, Clock clock) {
        this.delegate = delegate;
        this.cacheRepository = cacheRepository;
        this.clock = clock;
    }

    @Override
    public boolean isAvailable() {
        return delegate.isAvailable();
    }

    @Override
    public String explain(WordCard word) {
        String key = cacheKey(word);
        if (delegate.isAvailable()) {
            try {
                var cached = cacheRepository.find(key);
                if (cached.isPresent()) {
                    return cached.get();
                }
            } catch (SQLException ignored) {
                // Cache errors must not block review.
            }
        }
        String response = delegate.explain(word);
        if (delegate.isAvailable() && response != null && !response.isBlank()) {
            try {
                cacheRepository.save(key, response, LocalDateTime.now(clock));
            } catch (SQLException ignored) {
                // Cache errors must not block review.
            }
        }
        return response;
    }

    private String cacheKey(WordCard word) {
        String english = word == null || word.getEnglish() == null ? "" : word.getEnglish();
        String chinese = word == null || word.getChinese() == null ? "" : word.getChinese();
        return "explain:v1:" + english.trim().toLowerCase(Locale.ROOT) + ":" + chinese.trim();
    }
}
