package com.vocabtrainer.service;

import com.vocabtrainer.domain.LookupOutcome;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.domain.WordVerificationResult;
import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.util.ErrorMessages;
import com.vocabtrainer.util.Messages;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

import static com.vocabtrainer.util.Messages.tr;

/**
 * Checks the words that were added while the dictionaries could not be asked (tag
 * {@value #UNCHECKED}) once more. Each word is verified like "Add word" does it, through the whole
 * dictionary chain, which asks nothing online while offline mode is on: a word a dictionary has
 * loses {@value #UNCHECKED} and is tagged {@value #VERIFIED} and the dictionary, with its phonetic
 * when it had none; a word every dictionary answered not to have is tagged {@value #UNVERIFIED}; a
 * word that still cannot be checked keeps its tag.
 *
 * <p>Words are saved one by one as they are checked, so cancelling keeps what was done. Only the
 * tags (and an empty phonetic) are written, and only while the tags are as they were read, so an edit
 * or a review made meanwhile is never overwritten. After {@value #UNREACHABLE_IN_A_ROW} words in a
 * row could not be checked because a dictionary was unreachable, timed out, refused or limited the
 * requests, the check stops instead of waiting for every remaining word.
 */
public class UncheckedWordsService {
    public static final String UNCHECKED = "UNCHECKED";
    public static final String UNVERIFIED = "UNVERIFIED";
    public static final String VERIFIED = "VERIFIED";
    static final int UNREACHABLE_IN_A_ROW = 3;

    private static final Logger LOGGER = Logger.getLogger(UncheckedWordsService.class.getName());
    private static final Set<LookupOutcome> UNREACHABLE = Set.of(LookupOutcome.NETWORK_ERROR, LookupOutcome.TIMEOUT,
        LookupOutcome.AUTH_ERROR, LookupOutcome.RATE_LIMITED, LookupOutcome.SERVICE_ERROR);

    /** How far a check is: {@code checked} of {@code total} words. */
    public record Progress(int checked, int total) {
        public String toDisplayText() {
            return tr("recheck.progress", checked, total);
        }
    }

    /**
     * What a check did.
     *
     * @param stillUnchecked words that could not be checked again, or whose tags were edited meanwhile
     * @param stopped        whether it stopped early because the dictionaries could not be reached
     * @param offline        whether offline mode was on, so only the offline dictionaries were asked
     */
    public record Result(int total, int verified, int unverified, int stillUnchecked, boolean stopped,
                         String lastFailure, boolean offline) {
        public String toDisplayText() {
            if (total == 0) {
                return tr("recheck.none");
            }
            List<String> text = new ArrayList<>();
            text.add(tr("recheck.done", total, verified, unverified, stillUnchecked));
            if (stopped) {
                text.add(tr("recheck.stopped", lastFailure));
            } else if (offline && stillUnchecked > 0) {
                text.add(tr("recheck.offline"));
            }
            return Messages.sentences(text);
        }
    }

    private final WordRepository wordRepository;
    private final Supplier<DictionaryService> dictionary;
    private final BooleanSupplier offline;

    /**
     * @param dictionary the app's dictionary chain, taken when a check starts
     * @param offline    whether offline mode is on, which the chain itself respects
     */
    public UncheckedWordsService(WordRepository wordRepository, Supplier<DictionaryService> dictionary,
                                 BooleanSupplier offline) {
        this.wordRepository = wordRepository;
        this.dictionary = dictionary;
        this.offline = offline;
    }

    /** The decks' words tagged {@value #UNCHECKED}, suspended ones too. */
    public List<WordCard> findUnchecked(Collection<Long> deckIds) {
        try {
            return wordRepository.findTagged(deckIds, UNCHECKED);
        } catch (SQLException e) {
            throw new IllegalStateException(tr("recheck.error.read", ErrorMessages.rootMessage(e)), e);
        }
    }

    /**
     * Checks the decks' unchecked words again; see the class comment.
     *
     * @param progress  called after each word, on the calling thread
     * @param cancelled checked before each word; the words checked so far stay saved
     * @throws CancellationException when cancelled or interrupted
     */
    public Result recheck(Collection<Long> deckIds, Consumer<Progress> progress, BooleanSupplier cancelled) {
        boolean offlineMode = offline.getAsBoolean();
        List<WordCard> words = findUnchecked(deckIds);
        DictionaryService chain = words.isEmpty() ? null : dictionary.get();
        int verified = 0;
        int unverified = 0;
        int unreachableInARow = 0;
        String lastFailure = "";
        int checked = 0;
        progress.accept(new Progress(0, words.size()));
        for (WordCard word : words) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                throw new CancellationException("The check was canceled.");
            }
            WordVerificationResult result = chain.verify(word.getEnglish());
            if (result.outcome() == LookupOutcome.INTERRUPTED) {
                throw new CancellationException("The check was canceled.");
            }
            switch (result.status()) {
                case VERIFIED -> {
                    if (save(word, verifiedTags(word.getTags(), result.source()), result.phonetic())) {
                        verified++;
                    }
                    unreachableInARow = 0;
                }
                case UNVERIFIED -> {
                    if (save(word, retagged(word.getTags(), UNVERIFIED), "")) {
                        unverified++;
                    }
                    unreachableInARow = 0;
                }
                case UNCHECKED -> {
                    if (UNREACHABLE.contains(result.outcome())) {
                        unreachableInARow++;
                        lastFailure = result.message();
                    } else {
                        unreachableInARow = 0;
                    }
                }
            }
            progress.accept(new Progress(++checked, words.size()));
            if (unreachableInARow >= UNREACHABLE_IN_A_ROW && checked < words.size()) {
                LOGGER.warning("Stopped checking unchecked words: " + UNREACHABLE_IN_A_ROW
                    + " in a row could not be checked (" + result.outcome() + ")");
                return new Result(words.size(), verified, unverified, words.size() - verified - unverified, true,
                    lastFailure, offlineMode);
            }
        }
        LOGGER.info("Checked " + words.size() + " unchecked words: " + verified + " verified, " + unverified
            + " not in any dictionary");
        return new Result(words.size(), verified, unverified, words.size() - verified - unverified, false, lastFailure,
            offlineMode);
    }

    /** Saves the word's new tags unless they were edited since it was read; whether it was saved. */
    private boolean save(WordCard word, String tags, String phonetic) {
        try {
            return wordRepository.updateTagsIfUnchanged(word.getId(), word.getTags(), tags, phonetic);
        } catch (SQLException e) {
            throw new IllegalStateException(tr("recheck.error.save", word.getEnglish(), ErrorMessages.rootMessage(e)), e);
        }
    }

    /** The tags without {@value #UNCHECKED} and {@value #UNVERIFIED}, with {@value #VERIFIED} and the dictionary. */
    static String verifiedTags(String tags, String source) {
        String retagged = retagged(tags, VERIFIED);
        return source == null || source.isBlank() ? retagged : with(retagged, source.strip());
    }

    /**
     * The tags with {@code status} in place of the verification tags ({@value #UNCHECKED},
     * {@value #UNVERIFIED}, {@value #VERIFIED}), each tag once, separated by "; ".
     */
    static String retagged(String tags, String status) {
        List<String> kept = new ArrayList<>();
        for (String tag : split(tags)) {
            boolean verification = tag.equalsIgnoreCase(UNCHECKED) || tag.equalsIgnoreCase(UNVERIFIED)
                || tag.equalsIgnoreCase(VERIFIED);
            if (!verification) {
                kept.add(tag);
            }
        }
        return with(String.join("; ", kept), status);
    }

    private static String with(String tags, String tag) {
        List<String> all = new ArrayList<>(split(tags));
        if (all.stream().noneMatch(existing -> existing.equalsIgnoreCase(tag))) {
            all.add(tag);
        }
        return String.join("; ", all);
    }

    private static List<String> split(String tags) {
        return tags == null ? List.of()
            : Arrays.stream(tags.split("[;,]")).map(String::strip).filter(tag -> !tag.isEmpty()).toList();
    }
}
