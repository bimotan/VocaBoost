package com.vocabtrainer.service;

import com.vocabtrainer.domain.DictionaryEntry;
import com.vocabtrainer.domain.DictionaryLookupResult;
import com.vocabtrainer.domain.EcdictMetadata;
import com.vocabtrainer.domain.EcdictRow;
import com.vocabtrainer.domain.WordVerificationResult;
import com.vocabtrainer.repository.EcdictRepository;
import com.vocabtrainer.service.csv.CsvReader;
import com.vocabtrainer.service.csv.CsvRecord;
import com.vocabtrainer.service.csv.WordColumn;
import com.vocabtrainer.service.csv.WordColumns;
import com.vocabtrainer.service.ecdict.EcdictExchange;
import com.vocabtrainer.service.ecdict.EcdictTranslationCleaner;
import com.vocabtrainer.util.Messages;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import static com.vocabtrainer.util.Messages.tr;

/**
 * The offline dictionaries: the imported ECDICT dictionary (an indexed SQLite file, see
 * {@link EcdictRepository}), then the bundled GRE starter words. Nothing is loaded into memory
 * except the starter words, and creating the service never reads the ECDICT CSV.
 *
 * <p>Translations are cleaned when they are looked up ({@link EcdictTranslationCleaner}), so the
 * add form gets a clean Chinese answer key while the imported data stays as ECDICT has it. A word
 * that is not in ECDICT but is an inflection of an entry ("abandoned") finds its base form through
 * ECDICT's exchange field, and the result says so.
 */
public class LocalDictionaryService implements DictionaryService {
    public static final String ECDICT_SOURCE = "ECDICT/local CSV";
    public static final String STARTER_SOURCE = "Bundled GRE starter";
    /** The source of a word the offline dictionaries verified, which "Add word" adds to its tags. */
    public static final String LOCAL_SOURCE = "Local dictionary";

    private static final Logger LOGGER = Logger.getLogger(LocalDictionaryService.class.getName());
    private static final String STARTER_RESOURCE = "/data/gre_starter_sample.csv";
    /** ECDICT's pos column is a distribution such as "v:46/n:54", not a part of speech to show. */
    private static final Pattern POS_DISTRIBUTION = Pattern.compile("[a-z]+:\\d+(/[a-z]+:\\d+)*");
    private static final String FORM_KIND_ORDER = "pdi3rts";

    private final EcdictRepository ecdict;
    private final Map<String, DictionaryEntry> starterEntries;

    /** Only the bundled starter words. */
    public LocalDictionaryService() {
        this(null);
    }

    /** The imported ECDICT dictionary in {@code ecdict} (which may be empty), then the bundled starter words. */
    public LocalDictionaryService(EcdictRepository ecdict) {
        this.ecdict = ecdict;
        this.starterEntries = loadStarterEntries();
    }

    @Override
    public DictionaryLookupResult lookup(String english) {
        String key = normalizeKey(english);
        if (key.isBlank()) {
            return DictionaryLookupResult.notFound(tr("dictionary.enterWord"));
        }
        Optional<DictionaryEntry> entry = find(key);
        if (entry.isPresent()) {
            return DictionaryLookupResult.success(tr("dictionary.local.loaded"), List.of(entry.get()));
        }
        List<EcdictRepository.BaseForm> baseForms = findBaseForms(key);
        if (!baseForms.isEmpty()) {
            List<DictionaryEntry> entries = new ArrayList<>();
            List<String> explanations = new ArrayList<>();
            for (EcdictRepository.BaseForm baseForm : baseForms) {
                entries.add(toEntry(baseForm.row()));
                explanations.add(tr("dictionary.local.formOf", key, describeKinds(baseForm.kinds()),
                    baseForm.row().word()));
            }
            return DictionaryLookupResult.success(tr("dictionary.local.baseForms",
                    Messages.sentences(explanations), entries.size()), entries);
        }
        return DictionaryLookupResult.notFound(notFound());
    }

    @Override
    public WordVerificationResult verify(String english) {
        String key = normalizeKey(english);
        Optional<DictionaryEntry> entry = find(key);
        if (entry.isPresent()) {
            return WordVerificationResult.found(LOCAL_SOURCE, tr("dictionary.local.verified"))
                .withPhonetic(entry.get().phonetic());
        }
        List<EcdictRepository.BaseForm> baseForms = findBaseForms(key);
        if (!baseForms.isEmpty()) {
            return WordVerificationResult.found(LOCAL_SOURCE,
                tr("dictionary.local.verifiedForm", key, baseForms.get(0).row().word()));
        }
        return WordVerificationResult.missing(notFound());
    }

    @Override
    public boolean isConfigured() {
        return !starterEntries.isEmpty() || status().ecdictImported();
    }

    /**
     * The inflections the imported ECDICT dictionary lists for {@code english} in its exchange field
     * (past tense, participles, third person, comparative, superlative, plural), such as "forwent"
     * and "forgone" for forgo; empty when nothing is imported or the dictionary has no such entry.
     */
    public Set<String> inflections(String english) {
        String key = normalizeKey(english);
        if (ecdict == null || key.isBlank()) {
            return Set.of();
        }
        try {
            return ecdict.find(key)
                .map(row -> EcdictExchange.inflections(row.word(), row.exchange()))
                .orElse(Set.of());
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Cannot read the inflections of '" + key + "' in the imported ECDICT dictionary", e);
            return Set.of();
        }
    }

    /** What is imported and loaded; reads only the dictionary's metadata, never the CSV. */
    public LocalDictionaryStatus status() {
        EcdictMetadata imported = null;
        if (ecdict != null) {
            try {
                imported = ecdict.metadata().orElse(null);
            } catch (SQLException e) {
                LOGGER.log(Level.WARNING, "Cannot read the imported ECDICT dictionary " + ecdict.databasePath(), e);
            }
        }
        return new LocalDictionaryStatus(imported, starterEntries.size());
    }

    private Optional<DictionaryEntry> find(String key) {
        if (key.isBlank()) {
            return Optional.empty();
        }
        if (ecdict != null) {
            try {
                Optional<EcdictRow> row = ecdict.find(key);
                if (row.isPresent()) {
                    return Optional.of(toEntry(row.get()));
                }
            } catch (SQLException e) {
                // The starter words and the online dictionaries still answer.
                LOGGER.log(Level.WARNING, "Cannot look up '" + key + "' in the imported ECDICT dictionary", e);
            }
        }
        return Optional.ofNullable(starterEntries.get(key.toLowerCase(Locale.ROOT)));
    }

    private List<EcdictRepository.BaseForm> findBaseForms(String key) {
        if (ecdict == null || key.isBlank()) {
            return List.of();
        }
        try {
            return ecdict.findBaseForms(key);
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Cannot look up the base form of '" + key + "' in the imported ECDICT dictionary", e);
            return List.of();
        }
    }

    /**
     * The entry an ECDICT row gives: its translation cleaned into a meaning a learner can type, with
     * the part of speech and note from it ({@link EcdictTranslationCleaner}), its phonetic and its
     * English definition.
     */
    public static DictionaryEntry toEntry(EcdictRow row) {
        EcdictTranslationCleaner.Cleaned cleaned = EcdictTranslationCleaner.clean(row.translation());
        String pos = row.pos() == null || POS_DISTRIBUTION.matcher(row.pos()).matches() ? "" : row.pos();
        return new DictionaryEntry(
            row.word(),
            cleaned.meaning(),
            cleaned.partOfSpeech().isEmpty() ? pos : cleaned.partOfSpeech(),
            row.phonetic(),
            row.example(),
            ECDICT_SOURCE,
            EcdictTranslationCleaner.unescapeLines(row.definition()),
            cleaned.note()
        );
    }

    /** "the past tense and past participle" for "dp"; "a form" when ECDICT does not say. */
    private static String describeKinds(String kinds) {
        List<String> names = new ArrayList<>();
        for (char kind : FORM_KIND_ORDER.toCharArray()) {
            if (kinds != null && kinds.indexOf(kind) >= 0) {
                names.add(formKind(kind));
            }
        }
        return names.isEmpty() ? tr("dictionary.form.any") : tr("dictionary.form.kinds", String.join(tr("dictionary.form.and"), names));
    }

    private static String formKind(char kind) {
        return switch (kind) {
            case 'p' -> tr("dictionary.form.past");
            case 'd' -> tr("dictionary.form.pastParticiple");
            case 'i' -> tr("dictionary.form.presentParticiple");
            case '3' -> tr("dictionary.form.thirdPerson");
            case 'r' -> tr("dictionary.form.comparative");
            case 't' -> tr("dictionary.form.superlative");
            default -> tr("dictionary.form.plural");
        };
    }

    private static String notFound() {
        return tr("dictionary.local.notFound");
    }

    /**
     * How a dictionary is named on screen: the local dictionaries and the configured API by a name in
     * the app's language, the online ones by their own name. A source is also saved with cached
     * lookups and added to a word's tags, so it stays as it is there.
     */
    public static String sourceLabel(String source) {
        if (source == null) {
            return "";
        }
        return switch (source) {
            case ECDICT_SOURCE -> tr("dictionary.source.ecdict");
            case STARTER_SOURCE -> tr("dictionary.source.starter");
            case LOCAL_SOURCE -> tr("dictionary.source.local");
            case HttpDictionaryService.SOURCE -> tr("dictionary.source.api");
            default -> source;
        };
    }

    private static Map<String, DictionaryEntry> loadStarterEntries() {
        Map<String, DictionaryEntry> entries = new HashMap<>();
        InputStream stream = LocalDictionaryService.class.getResourceAsStream(STARTER_RESOURCE);
        if (stream == null) {
            LOGGER.warning("The bundled starter dictionary " + STARTER_RESOURCE + " is missing");
            return entries;
        }
        try (InputStream in = stream; CsvReader reader = CsvReader.open(in, StandardCharsets.UTF_8)) {
            WordColumns columns = null;
            CsvRecord record;
            while ((record = reader.read()) != null) {
                if (columns == null) {
                    columns = WordColumns.fromHeader(record)
                        .orElseThrow(() -> new IOException("The starter dictionary has no header row"));
                    continue;
                }
                String english = columns.get(record, WordColumn.ENGLISH).strip();
                EcdictTranslationCleaner.Cleaned cleaned =
                    EcdictTranslationCleaner.clean(columns.get(record, WordColumn.CHINESE));
                if (english.isEmpty() || cleaned.meaning().isEmpty()) {
                    continue;
                }
                entries.putIfAbsent(english.toLowerCase(Locale.ROOT), new DictionaryEntry(
                    english,
                    cleaned.meaning(),
                    columns.get(record, WordColumn.POS).strip(),
                    columns.get(record, WordColumn.PHONETIC).strip(),
                    columns.get(record, WordColumn.EXAMPLE).strip(),
                    STARTER_SOURCE
                ));
            }
        } catch (IOException e) {
            // A broken resource should not stop the app; the online dictionaries still work.
            LOGGER.log(Level.WARNING, "Cannot read bundled starter dictionary " + STARTER_RESOURCE, e);
        }
        return Map.copyOf(entries);
    }

    private static String normalizeKey(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ");
    }
}
