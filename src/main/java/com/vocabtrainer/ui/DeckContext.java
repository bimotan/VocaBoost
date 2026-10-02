package com.vocabtrainer.ui;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.service.DeckService;
import com.vocabtrainer.service.SettingsService;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The deck the window works on and the list of active decks. Switching to another deck remembers it
 * for the next launch ({@code ui.lastDeckId}).
 *
 * <p>Code that acts right away reads {@link #current()}. Work that finishes later, such as a
 * background import, captures the deck when it starts and keeps using that deck, because the user
 * may have switched in the meantime.
 */
public final class DeckContext {
    private static final Logger LOGGER = Logger.getLogger(DeckContext.class.getName());

    private final DeckService deckService;
    private final SettingsService settingsService;
    private final ReadOnlyObjectWrapper<Deck> current;
    private final ObservableList<Deck> activeDecks = FXCollections.observableArrayList();
    private final ObservableList<Deck> readOnlyActiveDecks = FXCollections.unmodifiableObservableList(activeDecks);
    private final List<Consumer<Deck>> switchListeners = new CopyOnWriteArrayList<>();

    public DeckContext(DeckService deckService, SettingsService settingsService, Deck startupDeck) {
        this.deckService = deckService;
        this.settingsService = settingsService;
        this.current = new ReadOnlyObjectWrapper<>(this, "current", Objects.requireNonNull(startupDeck, "startupDeck"));
    }

    /** The current deck; it changes on a switch and when the current deck is renamed. */
    public ReadOnlyObjectProperty<Deck> currentProperty() {
        return current.getReadOnlyProperty();
    }

    public Deck current() {
        return current.get();
    }

    public long currentId() {
        return current.get().getId();
    }

    /** The active decks, sorted as {@link DeckService#activeDecks()} returns them. */
    public ObservableList<Deck> activeDecks() {
        return readOnlyActiveDecks;
    }

    /**
     * Calls {@code listener} with the new deck whenever a different deck becomes current. Renaming the
     * current deck is not a switch.
     */
    public void onSwitch(Consumer<Deck> listener) {
        switchListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** Makes {@code deck} the current deck and re-reads the active decks. */
    public void switchTo(Deck deck) {
        reload(deck.getId());
    }

    /**
     * Re-reads the active decks after decks were created, renamed, archived or restored. The current
     * deck stays current while it is active; otherwise the first active deck becomes current.
     */
    public void reloadDecks() {
        reload(currentId());
    }

    private void reload(long preferredId) {
        List<Deck> decks = deckService.activeDecks();
        Deck selected = decks.stream()
            .filter(deck -> deck.getId() == preferredId)
            .findFirst()
            .orElse(decks.isEmpty() ? null : decks.get(0));
        activeDecks.setAll(decks);
        if (selected != null) {
            setCurrent(selected);
        }
    }

    private void setCurrent(Deck deck) {
        Deck previous = current.get();
        current.set(deck);
        if (previous.getId() != deck.getId()) {
            rememberCurrentDeck();
            Listeners.notifyAll(switchListeners, listener -> listener.accept(deck));
        }
    }

    /** Saves the current deck so the next launch opens it; failing to save must not block the switch. */
    private void rememberCurrentDeck() {
        try {
            settingsService.saveLastDeckId(currentId());
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Cannot remember the last used deck", e);
        }
    }
}
