package com.vocabtrainer.service;

import com.vocabtrainer.domain.DictionaryEntry;
import com.vocabtrainer.domain.DictionaryLookupResult;
import com.vocabtrainer.domain.LookupOutcome;
import com.vocabtrainer.repository.DictionaryCacheRepository;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Keeps what an online dictionary found in {@code dictionary_cache}, so the word is not looked up
 * online again for {@link #FOUND_TTL}. An older entry is looked up again, and is still shown, with
 * a note, while the dictionaries cannot be asked. "Not found" is remembered in memory for
 * {@link #NOT_FOUND_TTL} only, so adding a word a lookup just missed does not ask again; that the
 * dictionaries could not be asked (no network, timeout, refused) is never remembered. A refresh
 * ignores both and asks again.
 *
 * <p>Only the public online dictionaries are cached (see {@link DictionaryServiceFactory}): the
 * offline dictionaries and the configured API, which can give Chinese meanings, answer before the
 * cache, so an English-only entry cached earlier never hides them.
 */
public class CachingDictionaryService implements DictionaryService {
    /** How long a cached online result is used without asking the dictionary again. */
    public static final Duration FOUND_TTL = Duration.ofDays(30);
    /** How long "not found" is remembered. */
    public static final Duration NOT_FOUND_TTL = Duration.ofMinutes(10);

    private static final Logger LOGGER = Logger.getLogger(CachingDictionaryService.class.getName());
    private static final String BLANK_WORD = "Please enter an English word first.";
    /** What earlier versions wrote as the source of every cache entry, whichever dictionary answered. */
    private static final String LEGACY_SOURCE = "dictionary";

    private final DictionaryService delegate;
    private final DictionaryCacheRepository cacheRepository;
    private final Clock clock;
    private final Map<String, RememberedMiss> misses = new ConcurrentHashMap<>();

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
            return DictionaryLookupResult.notFound(BLANK_WORD);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        RememberedMiss miss = misses.get(missKey(key));
        if (miss != null && now.isBefore(miss.until())) {
            return miss.result();
        }
        Optional<Cached> cached = readCache(key);
        if (cached.isPresent() && cached.get().isFreshAt(now)) {
            return DictionaryLookupResult.success("Loaded from dictionary cache.", cached.get().entries());
        }
        return remember(key, delegate.lookup(key), cached, now);
    }

    @Override
    public DictionaryLookupResult refresh(String english) {
        String key = english == null ? "" : english.trim();
        if (key.isBlank()) {
            return DictionaryLookupResult.notFound(BLANK_WORD);
        }
        misses.remove(missKey(key));
        DictionaryLookupResult result = delegate.refresh(key);
        if (result.outcome() == LookupOutcome.NOT_FOUND) {
            // The dictionary no longer has the word, so a cached copy is out of date.
            deleteCache(key);
        }
        return remember(key, result, Optional.empty(), LocalDateTime.now(clock));
    }

    @Override
    public boolean isConfigured() {
        return delegate.isConfigured();
    }

    private DictionaryLookupResult remember(String key, DictionaryLookupResult result, Optional<Cached> expired,
                                            LocalDateTime now) {
        switch (result.outcome()) {
            case FOUND -> {
                misses.remove(missKey(key));
                if (isUsableCache(result.entries())) {
                    saveCache(key, result.entries(), now);
                }
                return result;
            }
            case NOT_FOUND -> {
                misses.values().removeIf(old -> !now.isBefore(old.until()));
                misses.put(missKey(key), new RememberedMiss(result, now.plus(NOT_FOUND_TTL)));
                if (expired.isPresent()) {
                    deleteCache(key);
                }
                return result;
            }
            case INTERRUPTED -> {
                return result;
            }
            default -> {
                // Better an old entry than none while the dictionaries cannot be asked.
                return expired
                    .map(entry -> DictionaryLookupResult.success("Loaded from dictionary cache (saved "
                        + entry.savedOn() + ") because the online dictionaries could not be asked: "
                        + result.message(), entry.entries()))
                    .orElse(result);
            }
        }
    }

    private Optional<Cached> readCache(String key) {
        try {
            Optional<DictionaryCacheRepository.CachedLookup> row = cacheRepository.find(key);
            if (row.isEmpty()) {
                return Optional.empty();
            }
            List<DictionaryEntry> entries = deserialize(row.get().payload());
            if (isUsableCache(entries) && !fromOfflineDictionary(entries) && !legacyLeftover(row.get(), entries)) {
                return Optional.of(new Cached(entries, row.get().createdAt()));
            }
            cacheRepository.delete(key);
        } catch (SQLException e) {
            // Cache errors should not block adding words.
            LOGGER.log(Level.WARNING, "Cannot read dictionary cache for '" + key + "'; looking it up instead", e);
        }
        return Optional.empty();
    }

    private void saveCache(String key, List<DictionaryEntry> entries, LocalDateTime now) {
        String source = entries.get(0).source() == null || entries.get(0).source().isBlank()
            ? "online dictionary" : entries.get(0).source();
        try {
            cacheRepository.save(key, serialize(entries), source, now);
        } catch (SQLException e) {
            // Cache errors should not block adding words.
            LOGGER.log(Level.WARNING, "Cannot save dictionary cache entry for '" + key + "'", e);
        }
    }

    private void deleteCache(String key) {
        try {
            cacheRepository.delete(key);
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Cannot delete dictionary cache entry for '" + key + "'", e);
        }
    }

    private static String missKey(String key) {
        return key.toLowerCase(Locale.ROOT);
    }

    /** A "not found" answer and when it stops being used. */
    private record RememberedMiss(DictionaryLookupResult result, LocalDateTime until) {
    }

    /** Cached entries and when they were saved ({@code null}: unknown, so expired). */
    private record Cached(List<DictionaryEntry> entries, LocalDateTime savedAt) {
        boolean isFreshAt(LocalDateTime now) {
            return savedAt != null && now.isBefore(savedAt.plus(FOUND_TTL));
        }

        String savedOn() {
            return savedAt == null ? "earlier" : savedAt.toLocalDate().toString();
        }
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

    /**
     * Earlier versions cached whatever the whole chain answered, with {@link #LEGACY_SOURCE} as the
     * source: the configured API's answers read by a regex parser that could garble them, and
     * Wiktionary's raw HTML from any language's section, including entries that only name a
     * misspelling. Only their dictionaryapi.dev entries were read as they are now; any other such
     * entry is looked up again instead of being used, even while the dictionaries cannot be asked.
     */
    private static boolean legacyLeftover(DictionaryCacheRepository.CachedLookup row, List<DictionaryEntry> entries) {
        return LEGACY_SOURCE.equals(row.source()) && entries.stream()
            .anyMatch(entry -> !PublicOnlineDictionaryService.DICTIONARY_API_SOURCE.equals(entry.source()));
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

    private List<DictionaryEntry> deserializeRows(String payload) {
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

    /** A payload this version cannot read counts as no entries, so the row is replaced. */
    private List<DictionaryEntry> deserialize(String payload) {
        try {
            return deserializeRows(payload == null ? "" : payload);
        } catch (IllegalArgumentException e) {
            LOGGER.log(Level.WARNING, "Ignoring a dictionary cache entry that cannot be read", e);
            return List.of();
        }
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
            || (source.toLowerCase(Locale.ROOT).contains("mock fallback")
                && !hasText(entry.definition()));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
