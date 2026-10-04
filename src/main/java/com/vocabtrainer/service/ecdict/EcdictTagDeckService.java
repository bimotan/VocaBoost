package com.vocabtrainer.service.ecdict;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.DictionaryEntry;
import com.vocabtrainer.domain.EcdictRow;
import com.vocabtrainer.domain.ValidatedWord;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.EcdictRepository;
import com.vocabtrainer.repository.TransactionRunner;
import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.service.DeckService;
import com.vocabtrainer.service.InOtherDecks;
import com.vocabtrainer.service.LocalDictionaryService;
import com.vocabtrainer.service.WordValidationService;
import com.vocabtrainer.util.ErrorMessages;
import com.vocabtrainer.util.Messages;

import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static com.vocabtrainer.util.Messages.tr;

/**
 * Builds a deck from the words ECDICT tags with an exam, such as every GRE word, most common first.
 *
 * <p>The words come from the imported ECDICT dictionary ({@code ecdict.db}); their meaning, part of
 * speech and note are cleaned as a lookup cleans them ({@link LocalDictionaryService#toEntry}), so the
 * answer key is one a learner can type, and the phonetic is ECDICT's. Each word is tagged with the
 * exam tag. A deck that does not exist yet is created; an active deck of that name is filled, and
 * the words it already has (suspended ones too) are skipped; no deck is created when there is no word
 * to put in it. The limit counts the words added, so building the same deck again with the same
 * limit adds the next words. Words without a Chinese
 * meaning or whose spelling the app does not accept ("a.m.") are skipped. Adding words earns no XP
 * and counts no new words.
 *
 * <p>A word another active deck already has is added with ECDICT's details, with that deck's meaning,
 * part of speech, example and phonetic, or not at all, as the request says ({@link InOtherDecks});
 * {@link #countInOtherDecks} tells beforehand how many words that concerns. A skipped word does not
 * count towards the limit. Which words those are, and the details copied, is settled before the first
 * batch is written.
 *
 * <p>The words are written in batches of {@value #BATCH_SIZE}, each in a short transaction of its own
 * (the first one also creates a new deck), with a short pause between them: a rating or another write
 * made meanwhile waits at most for one batch instead of for the whole deck, and so never runs into
 * the database's busy timeout. A cancel stops before the next batch and keeps the batches already
 * written; a failure keeps them too. Building the same deck again adds the rest, since the words the
 * deck has are skipped. Nothing is written, not even the deck, when it stops before the first batch.
 */
public class EcdictTagDeckService {
    /** Words are inserted, each batch in its own transaction, and the progress reported and the cancel checked, in batches of this many. */
    static final int BATCH_SIZE = 250;
    /**
     * The pause after each batch, so that a write that does not wait in line for the transaction lock
     * (a single statement on another thread) gets the database between two batches.
     */
    static final long BATCH_PAUSE_MILLIS = 10;

    private static final Logger LOGGER = Logger.getLogger(EcdictTagDeckService.class.getName());

    /** The exam tags of ECDICT's {@code tag} field. */
    public enum Tag {
        GRE("gre"),
        TOEFL("toefl"),
        IELTS("ielts"),
        CET4("cet4"),
        CET6("cet6"),
        KY("ky"),
        GK("gk"),
        ZK("zk");

        private final String code;

        Tag(String code) {
            this.code = code;
        }

        /** The tag as ECDICT writes it, such as "gre" or "ky". */
        public String code() {
            return code;
        }

        /** The exam's name in the app's language, such as "GRE" or "Kaoyan 考研" (考研 in Chinese). */
        public String label() {
            return switch (this) {
                case GRE -> tr("ecdict.tag.gre");
                case TOEFL -> tr("ecdict.tag.toefl");
                case IELTS -> tr("ecdict.tag.ielts");
                case CET4 -> tr("ecdict.tag.cet4");
                case CET6 -> tr("ecdict.tag.cet6");
                case KY -> tr("ecdict.tag.ky");
                case GK -> tr("ecdict.tag.gk");
                case ZK -> tr("ecdict.tag.zk");
            };
        }

        /** The deck name the form suggests, such as "GRE (ECDICT)". */
        public String defaultDeckName() {
            return tr("ecdict.tag.deckName", label());
        }

        /** The exam's name and the tag, as the form lists them: "GRE (gre)". */
        @Override
        public String toString() {
            return tr("ecdict.tag.choice", label(), code);
        }
    }

    /**
     * What to build.
     *
     * @param deckName     the deck to create, or the active deck to fill
     * @param limit        the most words to add; 0 for every word of the tag
     * @param inOtherDecks what to do with a word another active deck has; null reads as
     *                     {@link InOtherDecks#KEEP_IMPORTED}
     */
    public record Request(Tag tag, String deckName, int limit, EcdictRepository.TagOrder order,
                          InOtherDecks inOtherDecks) {
        /** Words other decks have are added with ECDICT's details. */
        public Request(Tag tag, String deckName, int limit, EcdictRepository.TagOrder order) {
            this(tag, deckName, limit, order, InOtherDecks.KEEP_IMPORTED);
        }

        /** This request with {@code choice} for the words other decks have. */
        public Request with(InOtherDecks choice) {
            return new Request(tag, deckName, limit, order, choice);
        }

        public Request {
            inOtherDecks = inOtherDecks == null ? InOtherDecks.KEEP_IMPORTED : inOtherDecks;
            if (tag == null || order == null) {
                throw new IllegalArgumentException(tr("ecdict.deck.error.tagOrder"));
            }
            if (deckName == null || deckName.isBlank()) {
                throw new IllegalArgumentException(tr("ecdict.deck.error.name"));
            }
            if (limit < 0) {
                throw new IllegalArgumentException(tr("ecdict.deck.error.limit"));
            }
        }
    }

    /**
     * What was built.
     *
     * @param deck          the deck created or filled; null when no deck was created because there was
     *                      no word to add to it (or the build stopped before the first batch)
     * @param created       whether the deck was created; false when an active deck was filled
     * @param added         words added to the deck
     * @param alreadyInDeck tagged words the deck already had, which were skipped
     * @param skipped       tagged words that cannot be added: no Chinese meaning or a spelling the app does not accept
     * @param tagged        words ECDICT tags with the exam
     * @param canceled      whether the build was canceled before every word was added; the words added
     *                      until then stay
     * @param inOtherDecks  words to add that another active deck has, which the build adds with their
     *                      details copied, adds as ECDICT has them, or skips, as {@code choice} says
     * @param choice        what was done with those words; null reads as {@link InOtherDecks#KEEP_IMPORTED}
     */
    public record Result(Deck deck, boolean created, int added, int alreadyInDeck, int skipped, int tagged,
                         boolean canceled, int inOtherDecks, InOtherDecks choice) {
        public Result {
            choice = choice == null ? InOtherDecks.KEEP_IMPORTED : choice;
        }

        /** A build that ran to the end and found no word another deck has. */
        public Result(Deck deck, boolean created, int added, int alreadyInDeck, int skipped, int tagged) {
            this(deck, created, added, alreadyInDeck, skipped, tagged, false, 0, InOtherDecks.KEEP_IMPORTED);
        }

        /** A build, canceled or not, that found no word another deck has. */
        public Result(Deck deck, boolean created, int added, int alreadyInDeck, int skipped, int tagged,
                      boolean canceled) {
            this(deck, created, added, alreadyInDeck, skipped, tagged, canceled, 0, InOtherDecks.KEEP_IMPORTED);
        }

        /** A build that ran to the end. */
        public Result(Deck deck, boolean created, int added, int alreadyInDeck, int skipped, int tagged,
                      int inOtherDecks, InOtherDecks choice) {
            this(deck, created, added, alreadyInDeck, skipped, tagged, false, inOtherDecks, choice);
        }

        /**
         * For example "Added 7,504 GRE words to GRE (ECDICT) (new deck). 12 already in the deck. 3 skipped:
         * already in other decks."; a canceled build says only how many words it added before it stopped,
         * since the counts of the words it left out were made for the whole deck.
         */
        public String toDisplayText(Tag tag) {
            if (canceled) {
                return deck == null || added == 0 ? tr("ecdict.deck.canceled")
                    : tr("ecdict.deck.canceledPartly", added, tag.label(), deck.getName());
            }
            List<String> text = new ArrayList<>();
            text.add(deck == null ? tr("ecdict.deck.noneAdded", tag.label())
                : created ? tr("ecdict.deck.addedNew", added, tag.label(), deck.getName())
                : tr("ecdict.deck.added", added, tag.label(), deck.getName()));
            if (alreadyInDeck > 0) {
                text.add(tr("ecdict.deck.alreadyIn", alreadyInDeck));
            }
            if (skipped > 0) {
                text.add(tr("ecdict.deck.skipped", skipped));
            }
            if (inOtherDecks > 0 && choice == InOtherDecks.COPY_DETAILS) {
                text.add(tr("ecdict.deck.inOtherDecks.copied", inOtherDecks));
            } else if (inOtherDecks > 0 && choice == InOtherDecks.SKIP) {
                text.add(tr("ecdict.deck.inOtherDecks.skipped", inOtherDecks));
            }
            if (tagged == 0) {
                text.add(tr("ecdict.deck.noTagged", tag.code()));
            }
            return Messages.sentences(text);
        }
    }

    /**
     * Writing a batch failed; the batches written before it stay in the deck, as {@link #partial()}
     * says (no deck and nothing added when the first batch failed).
     */
    public static final class BuildFailedException extends IllegalStateException {
        private final transient Result partial;

        BuildFailedException(String message, Throwable cause, Result partial) {
            super(message, cause);
            this.partial = partial;
        }

        /** What was added before the failure. */
        public Result partial() {
            return partial;
        }
    }

    /**
     * How far the build is.
     *
     * @param added words inserted so far
     * @param total words to insert; -1 while ECDICT is read
     */
    public record Progress(int added, int total) {
        public String toDisplayText() {
            return total < 0 ? tr("ecdict.deck.reading") : tr("ecdict.deck.adding", added, total);
        }
    }

    private final EcdictRepository ecdict;
    private final DeckService deckService;
    private final WordRepository wordRepository;
    private final WordValidationService validationService;
    private final TransactionRunner transactions;
    private final Clock clock;

    public EcdictTagDeckService(EcdictRepository ecdict, DeckService deckService, WordRepository wordRepository,
                                WordValidationService validationService) {
        this(ecdict, deckService, wordRepository, validationService, Clock.systemDefaultZone());
    }

    /** {@code clock} dates the words this adds. */
    public EcdictTagDeckService(EcdictRepository ecdict, DeckService deckService, WordRepository wordRepository,
                                WordValidationService validationService, Clock clock) {
        this.ecdict = ecdict;
        this.deckService = deckService;
        this.wordRepository = wordRepository;
        this.validationService = validationService;
        this.transactions = wordRepository.transactions();
        this.clock = clock;
    }

    /** Whether an ECDICT dictionary is imported, which building a deck needs. */
    public boolean isAvailable() {
        try {
            return ecdict.metadata().map(imported -> imported.rowCount() > 0).orElse(false);
        } catch (SQLException e) {
            throw new IllegalStateException(tr("ecdict.error.read", ErrorMessages.rootMessage(e)), e);
        }
    }

    /**
     * The words a build of {@code request} would add (by its limit, as if words other decks have were
     * kept) that another active deck already has: how many, and those decks' ids, oldest first.
     */
    public record OtherDecks(int words, List<Long> deckIds) {
        public OtherDecks {
            deckIds = List.copyOf(deckIds);
        }
    }

    /** See {@link OtherDecks}; reads ECDICT and the decks, writes nothing. */
    public OtherDecks countInOtherDecks(Request request) {
        if (!isAvailable()) {
            throw new IllegalStateException(tr("ecdict.error.notImported"));
        }
        try {
            List<EcdictRow> rows = ecdict.findByTag(request.tag().code(), request.order());
            Optional<Deck> existing = deckService.findActiveDeck(request.deckName());
            long deckId = existing.map(Deck::getId).orElse(0L);
            List<WordCard> words = select(request.with(InOtherDecks.KEEP_IMPORTED), rows, existing, Map.of()).words();
            Map<String, WordCard> elsewhere = wordRepository.findFirstInOtherDecks(
                words.stream().map(word -> word.getEnglish().toLowerCase(Locale.ROOT)).toList(), deckId);
            return new OtherDecks(elsewhere.size(),
                new TreeSet<>(elsewhere.values().stream().map(WordCard::getDeckId).toList()).stream().toList());
        } catch (SQLException e) {
            throw new IllegalStateException(tr("ecdict.error.read", ErrorMessages.rootMessage(e)), e);
        }
    }

    /**
     * Creates or fills the deck; see the class comment.
     *
     * @param progress  called from the calling thread as ECDICT is read and words are added
     * @param cancelled checked before each batch; when it says true (or the thread is interrupted) no
     *                  further batch is written and the result says it was canceled
     * @throws IllegalStateException when no ECDICT dictionary is imported, or reading fails
     * @throws BuildFailedException  when writing a batch fails
     */
    public Result build(Request request, Consumer<Progress> progress, BooleanSupplier cancelled) {
        if (!isAvailable()) {
            throw new IllegalStateException(tr("ecdict.error.notImported"));
        }
        progress.accept(new Progress(0, -1));
        List<EcdictRow> rows;
        try {
            rows = ecdict.findByTag(request.tag().code(), request.order());
        } catch (SQLException e) {
            throw new IllegalStateException(tr("ecdict.error.read", ErrorMessages.rootMessage(e)), e);
        }
        if (isCancelled(cancelled)) {
            return new Result(null, false, 0, 0, 0, rows.size(), true, 0, request.inOtherDecks());
        }
        Plan plan;
        try {
            plan = plan(request, rows);
        } catch (SQLException e) {
            throw new IllegalStateException(tr("ecdict.deck.error.add", ErrorMessages.rootMessage(e)), e);
        }
        Selection selection = plan.selection();
        if (plan.existing() == null && selection.words().isEmpty()) {
            // An empty deck would only be in the way.
            return new Result(null, false, 0, selection.alreadyInDeck(), selection.skipped(), rows.size(),
                selection.inOtherDecks(), request.inOtherDecks());
        }
        Result result = write(request, plan, rows.size(), progress, cancelled);
        if (result.deck() != null) {
            LOGGER.info("Added " + result.added() + " ECDICT " + request.tag().code() + " words to deck "
                + result.deck().getId() + (result.created() ? " (new)" : "") + (result.canceled() ? " (canceled)" : ""));
        }
        return result;
    }

    /**
     * The words to add, in order, and what was left out or changed.
     *
     * @param existing the active deck to fill; null when the deck is to be created
     */
    private record Plan(Deck existing, Selection selection) {
    }

    /**
     * Finds the deck to fill and, unless ECDICT's details are kept, the words other active decks have,
     * and selects the words to add; writes nothing.
     */
    private Plan plan(Request request, List<EcdictRow> rows) throws SQLException {
        Optional<Deck> existing = deckService.findActiveDeck(request.deckName());
        Map<String, WordCard> elsewhere = request.inOtherDecks() == InOtherDecks.KEEP_IMPORTED ? Map.of()
            : wordRepository.findFirstInOtherDecks(rows.stream()
                .map(row -> validationService.normalizeEnglish(row.word()).toLowerCase(Locale.ROOT)).toList(),
                existing.map(Deck::getId).orElse(0L));
        return new Plan(existing.orElse(null), select(request, rows, existing, elsewhere));
    }

    /** The words a build adds, and the tagged words it leaves out or changes and why. */
    private record Selection(List<WordCard> words, int alreadyInDeck, int skipped, int inOtherDecks) {
    }

    /**
     * The words to add, in ECDICT's order up to the limit: not those the deck has, nor those the app
     * cannot take; a word in {@code elsewhere} (another deck's word by lower-case English) is copied
     * from it or left out, as the request says.
     */
    private Selection select(Request request, List<EcdictRow> rows, Optional<Deck> existing,
                             Map<String, WordCard> elsewhere) throws SQLException {
        Set<String> inDeck = existing.isPresent() ? wordRepository.findEnglishKeys(existing.get().getId()) : new HashSet<>();
        List<WordCard> words = new ArrayList<>();
        int alreadyInDeck = 0;
        int skipped = 0;
        int inOtherDecks = 0;
        for (EcdictRow row : rows) {
            if (request.limit() > 0 && words.size() >= request.limit()) {
                break;
            }
            // The spelling as the deck stores it ("a  la carte" becomes "a la carte"), ignoring case.
            String key = validationService.normalizeEnglish(row.word()).toLowerCase(Locale.ROOT);
            if (inDeck.contains(key)) {
                alreadyInDeck++;
                continue;
            }
            Optional<WordCard> word = toWord(row, existing.map(Deck::getId).orElse(0L), request.tag());
            if (word.isEmpty()) {
                skipped++;
                continue;
            }
            WordCard other = elsewhere.get(key);
            if (other != null) {
                inOtherDecks++;
                if (request.inOtherDecks() == InOtherDecks.SKIP) {
                    continue;
                }
                InOtherDecks.copyDetails(other, word.get());
            }
            inDeck.add(word.get().getEnglish().toLowerCase(Locale.ROOT));
            words.add(word.get());
        }
        return new Selection(words, alreadyInDeck, skipped, inOtherDecks);
    }

    /** Writes the planned words batch by batch; see the class comment. */
    private Result write(Request request, Plan plan, int tagged, Consumer<Progress> progress,
                         BooleanSupplier cancelled) {
        Selection selection = plan.selection();
        List<WordCard> words = selection.words();
        Deck deck = plan.existing();
        boolean create = deck == null;
        int added = 0;
        int alreadyInDeck = selection.alreadyInDeck();
        progress.accept(new Progress(0, words.size()));
        for (int start = 0; start < words.size(); start += BATCH_SIZE) {
            if ((start > 0 && !pause()) || isCancelled(cancelled)) {
                return new Result(deck, create && deck != null, added, alreadyInDeck, selection.skipped(), tagged,
                    true, selection.inOtherDecks(), request.inOtherDecks());
            }
            List<WordCard> batch = words.subList(start, Math.min(words.size(), start + BATCH_SIZE));
            Deck target = deck;
            Batch written;
            try {
                written = transactions.inTransaction(() -> {
                    Deck into = target != null ? target : deckService.createDeck(request.deckName());
                    batch.forEach(word -> word.setDeckId(into.getId()));
                    // A word added to the deck meanwhile (on the add form) is skipped, not a failure.
                    return new Batch(into, wordRepository.insertAllIfAbsent(batch));
                });
            } catch (SQLException | RuntimeException e) {
                Result partial = new Result(deck, create && deck != null, added, alreadyInDeck, selection.skipped(),
                    tagged, selection.inOtherDecks(), request.inOtherDecks());
                // A deck name the deck service refuses explains itself.
                String message = e instanceof IllegalArgumentException ? e.getMessage()
                    : tr("ecdict.deck.error.add", ErrorMessages.rootMessage(e));
                throw new BuildFailedException(message, e, partial);
            }
            deck = written.deck();
            added += written.inserted();
            alreadyInDeck += batch.size() - written.inserted();
            progress.accept(new Progress(start + batch.size(), words.size()));
        }
        return new Result(deck, create, added, alreadyInDeck, selection.skipped(), tagged,
            selection.inOtherDecks(), request.inOtherDecks());
    }

    /** One batch as written: into which deck, and how many of its words were not in the deck yet. */
    private record Batch(Deck deck, int inserted) {
    }

    /** Waits {@value #BATCH_PAUSE_MILLIS} ms between two batches; false when the thread is interrupted. */
    private static boolean pause() {
        try {
            Thread.sleep(BATCH_PAUSE_MILLIS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The word for an ECDICT row, tagged with the exam; empty when the app cannot take it. */
    private Optional<WordCard> toWord(EcdictRow row, long deckId, Tag tag) {
        DictionaryEntry entry = LocalDictionaryService.toEntry(row);
        try {
            ValidatedWord validated = validationService.validate(row.word(), entry.chinese(), entry.phonetic(),
                entry.partOfSpeech(), "", entry.note(), tag.code());
            WordCard word = WordCard.createNew(deckId, validated.english(), validated.chinese(),
                LocalDateTime.now(clock));
            word.setPhonetic(validated.phonetic());
            word.setPartOfSpeech(validated.partOfSpeech());
            word.setNote(validated.note());
            word.setTags(validated.tags());
            return Optional.of(word);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static boolean isCancelled(BooleanSupplier cancelled) {
        return cancelled.getAsBoolean() || Thread.currentThread().isInterrupted();
    }
}
