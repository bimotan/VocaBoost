package com.vocabtrainer.service;

import com.vocabtrainer.domain.DictionaryEntry;
import com.vocabtrainer.domain.DictionaryLookupResult;
import com.vocabtrainer.repository.DictionaryCacheRepository;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class CachingDictionaryService implements DictionaryService {
    private static final Logger LOGGER = Logger.getLogger(CachingDictionaryService.class.getName());

    private final DictionaryService delegate;
    private final DictionaryCacheRepository cacheRepository;
    private final Clock clock;

    public CachingDictionaryService(DictionaryService delegate, DictionaryCacheRepository cacheRepository) {
        this(delegate, cacheRepository, Clock.systemDefaultZone());
    }

    public CachingDictionaryService(DictionaryService delegate, DictionaryCacheRepository cacheRepository, Clock clock) {
        this.delegate = delegate;
        this.cacheRepository = cacheRepository;
        this.clock = clock;
    }

    @Override
    public DictionaryLookupResult lookup(String english) {
        String key = english == null ? "" : english.trim();
        if (key.isBlank()) {
            return DictionaryLookupResult.notFound("Please enter an English word first.");
        }
        try {
            var cached = cacheRepository.findPayload(key);
            if (cached.isPresent()) {
                List<DictionaryEntry> cachedEntries = deserialize(cached.get());
                if (isUsableCache(cachedEntries) && !fromOfflineDictionary(cachedEntries)) {
                    return DictionaryLookupResult.success("Loaded from dictionary cache.", cachedEntries);
                }
                cacheRepository.delete(key);
            }
        } catch (SQLException e) {
            // Cache errors should not block adding words.
            LOGGER.log(Level.WARNING, "Cannot read dictionary cache for '" + key + "'; looking it up instead", e);
        }

        DictionaryLookupResult result = delegate.lookup(key);
        if (result.success() && isUsableCache(result.entries())) {
            try {
                cacheRepository.save(key, serialize(result.entries()), "dictionary", LocalDateTime.now(clock));
            } catch (SQLException e) {
                // Cache errors should not block adding words.
                LOGGER.log(Level.WARNING, "Cannot save dictionary cache entry for '" + key + "'", e);
            }
        }
        return result;
    }

    @Override
    public DictionaryLookupResult refresh(String english) {
        String key = english == null ? "" : english.trim();
        if (key.isBlank()) {
            return DictionaryLookupResult.notFound("Please enter an English word first.");
        }
        try {
            cacheRepository.delete(key);
        } catch (SQLException e) {
            // Cache refresh should still attempt a fresh lookup.
            LOGGER.log(Level.WARNING, "Cannot clear dictionary cache entry for '" + key + "'", e);
        }
        return lookup(key);
    }

    @Override
    public boolean isConfigured() {
        return delegate.isConfigured();
    }

    /**
     * Earlier versions cached what the offline dictionaries answered, with ECDICT's raw translation
     * ("vt. 放弃, ...\\nn. ..."). The offline dictionaries are no longer cached, so such an entry is a
     * leftover that must not answer while ECDICT is being imported or after it was cleared.
     */
    private static boolean fromOfflineDictionary(List<DictionaryEntry> entries) {
        return entries.stream().anyMatch(entry -> LocalDictionaryService.ECDICT_SOURCE.equals(entry.source())
            || LocalDictionaryService.STARTER_SOURCE.equals(entry.source()));
    }

    private String serialize(List<DictionaryEntry> entries) {
        List<String> rows = new ArrayList<>();
        for (DictionaryEntry entry : entries) {
            rows.add(String.join("\t",
                encode(entry.english()),
                encode(entry.chinese()),
                encode(entry.partOfSpeech()),
                encode(entry.phonetic()),
                encode(entry.example()),
                encode(entry.source()),
                encode(entry.definition()),
                encode(entry.note())
            ));
        }
        return String.join("\n", rows);
    }

    private List<DictionaryEntry> deserialize(String payload) {
        List<DictionaryEntry> entries = new ArrayList<>();
        for (String row : payload.split("\\R")) {
            if (row.isBlank()) {
                continue;
            }
            String[] fields = row.split("\\t", -1);
            if (fields.length >= 6 && fields.length <= 8) {
                String chinese = decode(fields[1]);
                String definition = fields.length >= 7 ? decode(fields[6]) : "";
                if (looksLikeOnlineDefinitionPlaceholder(chinese)) {
                    definition = extractDefinition(chinese);
                    chinese = "";
                }
                entries.add(new DictionaryEntry(
                    decode(fields[0]),
                    chinese,
                    decode(fields[2]),
                    decode(fields[3]),
                    decode(fields[4]),
                    decode(fields[5]),
                    definition,
                    fields.length == 8 ? decode(fields[7]) : ""
                ));
            }
        }
        return entries;
    }

    private String encode(String value) {
        String safe = value == null ? "" : value;
        return Base64.getEncoder().encodeToString(safe.getBytes(StandardCharsets.UTF_8));
    }

    private String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private boolean looksLikeOnlineDefinitionPlaceholder(String value) {
        return value != null && value.startsWith("请填写中文释义（English definition: ");
    }

    private String extractDefinition(String value) {
        if (!looksLikeOnlineDefinitionPlaceholder(value)) {
            return value == null ? "" : value;
        }
        String prefix = "请填写中文释义（English definition: ";
        String definition = value.substring(prefix.length());
        if (definition.endsWith("）")) {
            definition = definition.substring(0, definition.length() - 1);
        }
        return definition.trim();
    }

    private boolean isUsableCache(List<DictionaryEntry> entries) {
        return entries != null && !entries.isEmpty()
            && entries.stream().noneMatch(this::isMockFallbackEntry)
            && entries.stream().anyMatch(entry -> hasText(entry.chinese()) || hasText(entry.definition()));
    }

    private boolean isMockFallbackEntry(DictionaryEntry entry) {
        if (entry == null) {
            return true;
        }
        String source = entry.source() == null ? "" : entry.source().trim();
        String chinese = entry.chinese() == null ? "" : entry.chinese().trim();
        return source.equalsIgnoreCase("Mock fallback")
            || chinese.equals("请手动填写中文释义")
            || (source.toLowerCase(java.util.Locale.ROOT).contains("mock fallback")
                && !hasText(entry.definition()));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
