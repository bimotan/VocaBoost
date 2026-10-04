package com.vocabtrainer.service;

import com.vocabtrainer.domain.DictionaryEntry;
import com.vocabtrainer.domain.DictionaryLookupResult;
import com.vocabtrainer.domain.LookupOutcome;
import com.vocabtrainer.repository.DictionaryCacheRepository;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.vocabtrainer.util.Messages.tr;

/**
 * A word's synonyms, antonyms and pronunciation recording ({@link WordExtras}), which only the public
 * online dictionaries give (dictionaryapi.dev). They are kept with the online lookup in
 * {@code dictionary_cache}, so showing them reads the cache and sends nothing; looking a word up for
 * them ({@link #lookUp}) and downloading a recording ({@link #audioFile}) are the only requests,
 * made when the user asks, and never while offline mode is on.
 *
 * <p>Recordings are downloaded once per run into a temporary folder that is deleted when the app
 * quits; a recording over {@value #MAX_AUDIO_BYTES} bytes is refused.
 */
public class WordExtrasService {
    /** The largest recording downloaded; dictionaryapi.dev's are about 20 KB. */
    static final int MAX_AUDIO_BYTES = 2_000_000;
    private static final Duration AUDIO_TIMEOUT = Duration.ofSeconds(10);
    private static final Pattern EXTENSION = Pattern.compile("\\.([A-Za-z0-9]{2,4})$");
    private static final Logger LOGGER = Logger.getLogger(WordExtrasService.class.getName());

    /** What looking a word up for its extras gave: the extras (perhaps none) and how the lookup ended. */
    public record Lookup(WordExtras extras, LookupOutcome outcome, String message) {
    }

    private final DictionaryCacheRepository cacheRepository;
    private final DictionaryService online;
    private final BooleanSupplier offline;
    private final HttpClient audioClient;
    private final Map<String, Path> recordings = new ConcurrentHashMap<>();
    private Path audioFolder;

    /**
     * @param online  the public online dictionaries behind the lookup cache, see
     *                {@link DictionaryServiceFactory#publicOnline}; null when there are none
     * @param offline whether offline mode is on, read at every request
     */
    public WordExtrasService(DictionaryCacheRepository cacheRepository, DictionaryService online,
                             BooleanSupplier offline, HttpClient audioClient) {
        this.cacheRepository = cacheRepository;
        this.online = online;
        this.offline = offline;
        this.audioClient = audioClient;
    }

    /** Whether offline mode is on, so nothing can be looked up or downloaded. */
    public boolean isOffline() {
        return offline.getAsBoolean();
    }

    /** Whether words can be looked up online for their extras: there is a dictionary and offline mode is off. */
    public boolean canLookUp() {
        return online != null && !isOffline();
    }

    /** What the lookup cache holds for {@code english}; sends nothing. None when it cannot be read. */
    public WordExtras cached(String english) {
        String key = english == null ? "" : english.strip();
        if (key.isEmpty()) {
            return WordExtras.NONE;
        }
        try {
            Optional<DictionaryCacheRepository.CachedLookup> row = cacheRepository.find(key);
            return row.map(cached -> WordExtras.of(key, DictionaryCachePayload.deserialize(cached.payload())))
                .orElse(WordExtras.NONE);
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Cannot read the dictionary cache for the extras of '" + key + "'", e);
            return WordExtras.NONE;
        }
    }

    /**
     * Looks {@code english} up in the public online dictionaries again (whatever is cached, since a
     * cached entry of an earlier version has no extras) and returns its extras. While offline mode is
     * on, nothing is sent and the outcome is {@link LookupOutcome#OFFLINE}.
     */
    public Lookup lookUp(String english) {
        String key = english == null ? "" : english.strip();
        if (online == null || isOffline()) {
            return new Lookup(cached(key), LookupOutcome.OFFLINE, tr("dictionary.offline", tr("dictionary.online.name")));
        }
        DictionaryLookupResult result = online.refresh(key);
        List<DictionaryEntry> entries = result.entries();
        return new Lookup(result.success() ? WordExtras.of(key, entries) : cached(key), result.outcome(),
            result.message());
    }

    /**
     * The recording at {@code url} as a local file, downloaded on first use. Refused while offline
     * mode is on and for anything but an http or https address.
     *
     * @throws IOException           when it cannot be downloaded or saved
     * @throws IllegalStateException with a message for the user, e.g. while offline mode is on
     */
    public Path audioFile(String url) throws IOException {
        if (isOffline()) {
            throw new IllegalStateException(tr("extras.audio.offline"));
        }
        URI uri = audioUri(url);
        Path known = recordings.get(uri.toString());
        if (known != null && Files.isRegularFile(known)) {
            return known;
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(AUDIO_TIMEOUT)
            .header("User-Agent", HttpLookup.USER_AGENT)
            .GET()
            .build();
        HttpResponse<InputStream> response;
        try {
            response = audioClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(tr("extras.audio.interrupted"), e);
        }
        Path folder = audioFolder();
        Path target = folder.resolve(sha256(uri.toString()) + extension(uri));
        Path partial = Files.createTempFile(folder, "download", ".part");
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new IllegalStateException(tr("extras.audio.http", String.valueOf(response.statusCode())));
            }
            copyAtMost(body, partial);
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(partial);
        }
        target.toFile().deleteOnExit();
        recordings.put(uri.toString(), target);
        return target;
    }

    private static URI audioUri(String url) {
        try {
            URI uri = URI.create(url == null ? "" : url.strip());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((scheme.equals("https") || scheme.equals("http")) && uri.getHost() != null) {
                return uri;
            }
        } catch (IllegalArgumentException e) {
            // Reported below.
        }
        throw new IllegalStateException(tr("extras.audio.badUrl"));
    }

    private static void copyAtMost(InputStream body, Path target) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        long total = 0;
        try (OutputStream out = Files.newOutputStream(target)) {
            for (int read = body.read(buffer); read >= 0; read = body.read(buffer)) {
                total += read;
                if (total > MAX_AUDIO_BYTES) {
                    throw new IllegalStateException(tr("extras.audio.tooLarge"));
                }
                out.write(buffer, 0, read);
            }
        }
    }

    private synchronized Path audioFolder() throws IOException {
        if (audioFolder == null || !Files.isDirectory(audioFolder)) {
            audioFolder = Files.createTempDirectory("vocaboost-audio");
            audioFolder.toFile().deleteOnExit();
        }
        return audioFolder;
    }

    /** ".mp3" for ".../abate-us.mp3"; the media player tells formats by it. */
    private static String extension(URI uri) {
        Matcher matcher = EXTENSION.matcher(uri.getPath() == null ? "" : uri.getPath());
        return matcher.find() ? "." + matcher.group(1).toLowerCase(Locale.ROOT) : ".mp3";
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
