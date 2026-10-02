package com.vocabtrainer.service;

import com.vocabtrainer.repository.DictionaryCacheRepository;

import java.net.http.HttpClient;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class DictionaryServiceFactory {
    /** The configured dictionary API's address; without it there is none. */
    public static final String API_BASE_URL = "DICTIONARY_API_BASE_URL";
    /** The configured dictionary API's key, sent as a bearer token and as {@code X-API-Key}. */
    public static final String API_KEY = "DICTIONARY_API_KEY";

    private DictionaryServiceFactory() {
    }

    /** The app's dictionary chain, with the configured API taken from the environment. */
    public static DictionaryService create(DictionaryCacheRepository cacheRepository, LocalDictionaryService local) {
        return create(cacheRepository, local, System.getenv(), Clock.systemDefaultZone());
    }

    /**
     * The app's dictionary chain, with the configured API taken from {@code env}
     * ({@link #API_BASE_URL}, {@link #API_KEY}). The online dictionaries share one HTTP client.
     */
    public static DictionaryService create(DictionaryCacheRepository cacheRepository, LocalDictionaryService local,
                                           Map<String, String> env, Clock clock) {
        HttpClient httpClient = HttpLookup.newClient();
        String baseUrl = env.get(API_BASE_URL);
        DictionaryService configuredApi = baseUrl == null || baseUrl.isBlank()
            ? null
            : new HttpDictionaryService(baseUrl, env.get(API_KEY), httpClient);
        return compose(local, configuredApi, new PublicOnlineDictionaryService(httpClient), cacheRepository, clock);
    }

    /**
     * The chain, in the order its dictionaries are asked:
     * <ol>
     *   <li>the offline dictionaries ({@code local}: imported ECDICT, then the bundled starter words);</li>
     *   <li>the configured API, if there is one ({@code configuredApi} may be null);</li>
     *   <li>the public online dictionaries ({@code online}) through the lookup cache.</li>
     * </ol>
     * Only the public online dictionaries, which give English definitions only, are cached. The
     * dictionaries that can give Chinese meanings answer before the cache, so a re-imported ECDICT
     * or a newly configured API is never hidden by an English-only entry cached earlier.
     */
    public static DictionaryService compose(DictionaryService local, DictionaryService configuredApi,
                                            DictionaryService online, DictionaryCacheRepository cacheRepository,
                                            Clock clock) {
        List<DictionaryService> chain = new ArrayList<>();
        chain.add(local);
        if (configuredApi != null) {
            chain.add(configuredApi);
        }
        chain.add(new CachingDictionaryService(online, cacheRepository, clock));
        return new CompositeDictionaryService(chain);
    }
}
