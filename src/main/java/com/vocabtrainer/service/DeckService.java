package com.vocabtrainer.service;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.repository.DeckRepository;

import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static com.vocabtrainer.util.Messages.tr;

public class DeckService {
    private static final int MAX_DECK_NAME_LENGTH = 60;

    private final DeckRepository deckRepository;
    private final SettingsService settingsService;

    public DeckService(DeckRepository deckRepository, SettingsService settingsService) {
        this.deckRepository = deckRepository;
        this.settingsService = settingsService;
    }

    /** The deck a new database starts with, named in the app's language. */
    public Deck ensureDefaultDeck() {
        try {
            return deckRepository.ensureDefaultDeck(defaultDeckName());
        } catch (SQLException e) {
            throw new IllegalStateException(tr("deck.error.defaultDeckFailed"), e);
        }
    }

    /** The name of the deck a new database starts with, in the app's language. */
    public static String defaultDeckName() {
        return tr("deck.defaultName");
    }

    /**
     * The deck the app opens on: the last deck used if it is still active, otherwise the oldest
     * active deck. If every deck is archived the newest one is restored; a default deck is created
     * only when the database has no decks at all. Decks are never looked up by name here, so
     * renaming or archiving the default deck cannot break startup.
     */
    public Deck resolveStartupDeck() {
        try {
            Optional<Long> lastDeckId = settingsService.getLastDeckId();
            if (lastDeckId.isPresent()) {
                Optional<Deck> lastDeck = deckRepository.findById(lastDeckId.get());
                if (lastDeck.isPresent() && !lastDeck.get().isArchived()) {
                    return lastDeck.get();
                }
            }
            Deck deck = oldestActiveDeckOrRecover();
            settingsService.saveLastDeckId(deck.getId());
            return deck;
        } catch (SQLException e) {
            throw new IllegalStateException(tr("deck.error.openFailed"), e);
        }
    }

    public List<Deck> activeDecks() {
        try {
            List<Deck> decks = deckRepository.findAllActive();
            if (decks.isEmpty()) {
                decks = List.of(oldestActiveDeckOrRecover());
            }
            return decks;
        } catch (SQLException e) {
            throw new IllegalStateException(tr("deck.error.readFailed"), e);
        }
    }

    public List<Deck> archivedDecks() {
        try {
            return deckRepository.findAllArchived();
        } catch (SQLException e) {
            throw new IllegalStateException(tr("deck.error.readArchivedFailed"), e);
        }
    }

    /**
     * The active deck named {@code name}, with its spaces trimmed and collapsed as {@link #createDeck}
     * does and ignoring upper and lower case; empty when there is none.
     */
    public Optional<Deck> findActiveDeck(String name) {
        String cleanName = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        try {
            return cleanName.isEmpty() ? Optional.empty() : deckRepository.findByName(cleanName);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot look up the deck " + cleanName, e);
        }
    }

    public Deck createDeck(String name) {
        String cleanName = validateName(name);
        try {
            rejectNameInUse(cleanName, null);
            return deckRepository.create(cleanName);
        } catch (SQLException e) {
            throw new IllegalArgumentException(tr("deck.error.createNameInUse"), e);
        }
    }

    public Deck renameDeck(long id, String name) {
        String cleanName = validateName(name);
        try {
            rejectNameInUse(cleanName, id);
            return deckRepository.rename(id, cleanName);
        } catch (SQLException e) {
            throw new IllegalArgumentException(tr("deck.error.renameNameInUse"), e);
        }
    }

    /** Archives an active deck and returns the deck to switch to. The last active deck cannot be archived. */
    public Deck archiveDeck(long id) {
        try {
            List<Deck> decks = deckRepository.findAllActive();
            if (decks.stream().noneMatch(deck -> deck.getId() == id)) {
                throw new IllegalArgumentException(tr("deck.error.notActive"));
            }
            if (decks.size() <= 1) {
                throw new IllegalArgumentException(tr("deck.error.lastActive"));
            }
            deckRepository.archive(id);
            List<Deck> remaining = deckRepository.findAllActive();
            return remaining.isEmpty() ? oldestActiveDeckOrRecover() : remaining.get(0);
        } catch (SQLException e) {
            throw new IllegalStateException(tr("deck.error.archiveFailed"), e);
        }
    }

    /**
     * Restores an archived deck. Its name may have been reused by an active deck since it was
     * archived; then the user has to rename one of them first.
     */
    public Deck restoreDeck(long id) {
        try {
            Optional<Deck> deck = deckRepository.findById(id);
            if (deck.isPresent() && deck.get().isArchived()) {
                Optional<Deck> active = deckRepository.findByName(deck.get().getName());
                if (active.isPresent() && active.get().getId() != id) {
                    throw new IllegalArgumentException(
                        DeckRepository.restoreNameInUse(deck.get().getName(), active.get().getName()));
                }
            }
            return deckRepository.restore(id);
        } catch (SQLException e) {
            throw new IllegalArgumentException(tr("deck.error.restoreFailed", e.getMessage()), e);
        }
    }

    /**
     * The active deck with the lowest id. With no active deck, the newest archived deck is
     * restored, and the default deck is created only in a database without any decks.
     */
    private Deck oldestActiveDeckOrRecover() throws SQLException {
        Optional<Deck> oldestActive = deckRepository.findAllActive().stream()
            .min(Comparator.comparingLong(Deck::getId));
        if (oldestActive.isPresent()) {
            return oldestActive.get();
        }
        Optional<Deck> newestArchived = deckRepository.findAllArchived().stream()
            .max(Comparator.comparingLong(Deck::getId));
        if (newestArchived.isPresent()) {
            return deckRepository.restore(newestArchived.get().getId());
        }
        return deckRepository.ensureDefaultDeck(defaultDeckName());
    }

    /**
     * Names are unique among active decks, ignoring upper and lower case ("GRE" and "gre" are one
     * name); an archived deck's name can be used again, and a deck can be renamed to its own name in
     * other case.
     */
    private void rejectNameInUse(String name, Long renamingId) throws SQLException {
        Optional<Deck> existing = deckRepository.findByName(name);
        if (existing.isEmpty() || (renamingId != null && existing.get().getId() == renamingId)) {
            return;
        }
        String existingName = existing.get().getName();
        throw new IllegalArgumentException(existingName.equals(name) ? tr("deck.error.nameInUse", name)
            : tr("deck.error.nameInUseCase", existingName, name));
    }

    private String validateName(String name) {
        String cleanName = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (cleanName.isBlank()) {
            throw new IllegalArgumentException(tr("deck.error.nameEmpty"));
        }
        if (cleanName.length() > MAX_DECK_NAME_LENGTH) {
            throw new IllegalArgumentException(tr("deck.error.nameTooLong", MAX_DECK_NAME_LENGTH));
        }
        return cleanName;
    }
}
