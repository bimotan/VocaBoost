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

    private static final Logger LOGGER = Logger.getLogger(LocalDictionaryService.class.getName());
    private static final String STARTER_RESOURCE = "/data/gre_starter_sample.csv";
    private static final String NOT_FOUND = "词条未找到：本地词库没有该词条。";
    /** ECDICT's pos column is a distribution such as "v:46/n:54", not a part of speech to show. */
    private static final Pattern POS_DISTRIBUTION = Pattern.compile("[a-z]+:\\d+(/[a-z]+:\\d+)*");
    private static final String FORM_KIND_ORDER = "pdi3rts";
    private static final Map<Character, String> FORM_KINDS = Map.of(
        'p', "past tense", 'd', "past participle", 'i', "present participle", '3', "third-person singular",
        'r', "comparative", 't', "superlative", 's', "plural");

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
            return DictionaryLookupResult.notFound("Please enter an English word first.");
        }
        Optional<DictionaryEntry> entry = find(key);
        if (entry.isPresent()) {
            return DictionaryLookupResult.success("Loaded from local dictionary.", List.of(entry.get()));
        }
        List<EcdictRepository.BaseForm> baseForms = findBaseForms(key);
        if (!baseForms.isEmpty()) {
            List<DictionaryEntry> entries = new ArrayList<>();
            List<String> explanations = new ArrayList<>();
            for (EcdictRepository.BaseForm baseForm : baseForms) {
                entries.add(toEntry(baseForm.row()));
                explanations.add(key + " is " + describeKinds(baseForm.kinds()) + " " + baseForm.row().word() + ".");
            }
            return DictionaryLookupResult.success("Not in the local dictionary as written: "
                + String.join(" ", explanations) + (entries.size() == 1 ? " Showing the base form." : " Showing the base forms."),
                entries);
        }
        return DictionaryLookupResult.notFound(NOT_FOUND);
    }

    @Override
    public WordVerificationResult verify(String english) {
        String key = normalizeKey(english);
        Optional<DictionaryEntry> entry = find(key);
        if (entry.isPresent()) {
            return WordVerificationResult.found("Local dictionary", "本地词库已验证该词条。")
                .withPhonetic(entry.get().phonetic());
        }
        List<EcdictRepository.BaseForm> baseForms = findBaseForms(key);
        if (!baseForms.isEmpty()) {
            return WordVerificationResult.found("Local dictionary",
                "本地词库已验证该词条：" + key + " 是 " + baseForms.get(0).row().word() + " 的变形。");
        }
        return WordVerificationResult.missing(NOT_FOUND);
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

    /** "the past tense and past participle of" for "dp"; "a form of" when ECDICT does not say. */
    private static String describeKinds(String kinds) {
        List<String> names = new ArrayList<>();
        for (char kind : FORM_KIND_ORDER.toCharArray()) {
            if (kinds != null && kinds.indexOf(kind) >= 0) {
                names.add(FORM_KINDS.get(kind));
            }
        }
        return names.isEmpty() ? "a form of" : "the " + String.join(" and ", names) + " of";
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
