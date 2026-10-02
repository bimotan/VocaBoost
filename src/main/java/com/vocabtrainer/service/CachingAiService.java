package com.vocabtrainer.service;

import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.AiCacheRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Caches the delegate's explanations in {@code ai_cache}. Wrap the real provider directly, not a
 * {@link FallbackAiService}: whatever the delegate returns is cached, so a fallback text would be
 * served for that word forever.
 */
public class CachingAiService implements AiService {
    private static final Logger LOGGER = Logger.getLogger(CachingAiService.class.getName());
    private static final String KEY_PREFIX = "explain:v2:";

    private final AiService delegate;
    private final AiCacheRepository cacheRepository;
    private final String providerIdentity;
    private final Clock clock;

    /**
     * @param providerIdentity what produces the explanations (endpoint, model, prompt version);
     *                         it is part of every cache key, so a different provider or model
     *                         never reuses another one's cached text
     */
    public CachingAiService(AiService delegate, AiCacheRepository cacheRepository, String providerIdentity) {
        this(delegate, cacheRepository, providerIdentity, Clock.systemDefaultZone());
    }

    public CachingAiService(AiService delegate, AiCacheRepository cacheRepository, String providerIdentity,
                            Clock clock) {
        this.delegate = delegate;
        this.cacheRepository = cacheRepository;
        this.providerIdentity = providerIdentity == null ? "" : providerIdentity;
        this.clock = clock;
    }

    @Override
    public boolean isAvailable() {
        return delegate.isAvailable();
    }

    @Override
    public String explain(WordCard word) {
        return explain(ExplanationRequest.of(word));
    }

    @Override
    public String explain(ExplanationRequest request) {
        String key = cacheKey(request);
        if (delegate.isAvailable()) {
            try {
                var cached = cacheRepository.find(key);
                if (cached.isPresent()) {
                    return cached.get();
                }
            } catch (SQLException e) {
                // Cache errors must not block review.
                LOGGER.log(Level.WARNING, "Cannot read AI explanation cache; asking the provider instead", e);
            }
        }
        return askAndSave(key, request);
    }

    /** Asks the provider again, whatever is cached, and replaces the cached explanation. */
    @Override
    public String regenerate(ExplanationRequest request) {
        return askAndSave(cacheKey(request), request);
    }

    private String askAndSave(String key, ExplanationRequest request) {
        String response = delegate.explain(request);
        if (delegate.isAvailable() && response != null && !response.isBlank()) {
            try {
                cacheRepository.save(key, response, LocalDateTime.now(clock));
            } catch (SQLException e) {
                // Cache errors must not block review.
                LOGGER.log(Level.WARNING, "Cannot save AI explanation to cache", e);
            }
        }
        return response;
    }

    /**
     * {@code explain:v2:<english>:<sha-256>}. The hash covers the provider identity and everything
     * the prompt sends ({@link OpenAiCompatibleAiService#prompt}): the word's fields, the question's
     * direction, the learner's answer and a memory aid's focus, so the key stays short, an
     * explanation written for one answer is not shown for another, and the base URL is not stored in
     * clear text. The keys of plain explanations are those earlier versions used.
     */
    String cacheKey(ExplanationRequest request) {
        WordCard word = request.word();
        String english = clean(word.getEnglish()).toLowerCase(Locale.ROOT);
        String direction = request.hasAnswer() ? request.direction().name() : "";
        String answer = request.hasAnswer() ? request.normalizedAnswer() : "";
        String hashed = String.join("\0", providerIdentity, english, clean(word.getChinese()),
            clean(word.getExampleSentence()), clean(word.getPartOfSpeech()), direction, answer);
        if (request.focus() == ExplanationRequest.Focus.MEMORY_AID) {
            hashed += "\0memory-aid";
        }
        return KEY_PREFIX + english + ":" + sha256(hashed);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
