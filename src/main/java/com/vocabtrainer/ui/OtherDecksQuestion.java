package com.vocabtrainer.ui;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.service.InOtherDecks;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.vocabtrainer.util.Messages.tr;

/**
 * Asks what a word list import or an ECDICT tag deck does with the words other active decks already
 * have: copy their meanings, keep the imported ones or skip them ({@link InOtherDecks}).
 */
public final class OtherDecksQuestion {
    private OtherDecksQuestion() {
    }

    /**
     * Asks about {@code words} words that the decks {@code deckIds} already have; empty when the user
     * cancels, so nothing is imported.
     */
    public static Optional<InOtherDecks> ask(ViewContext context, int words, List<Long> deckIds) {
        String decks = deckIds.stream().map(id -> deckName(context, id)).distinct()
            .collect(Collectors.joining(tr("format.listSeparator")));
        ButtonType copy = new ButtonType(tr("otherDecks.copy"), ButtonBar.ButtonData.OK_DONE);
        ButtonType keep = new ButtonType(tr("otherDecks.keep"), ButtonBar.ButtonData.OTHER);
        ButtonType skip = new ButtonType(tr("otherDecks.skip"), ButtonBar.ButtonData.OTHER);
        ButtonType cancel = new ButtonType(tr("common.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        Optional<ButtonType> choice = context.dialogs().choose(tr("otherDecks.title"),
            tr("otherDecks.header", words, decks), tr("otherDecks.content"), copy, keep, skip, cancel);
        if (choice.isEmpty() || choice.get() == cancel) {
            return Optional.empty();
        }
        return Optional.of(choice.get() == copy ? InOtherDecks.COPY_DETAILS
            : choice.get() == skip ? InOtherDecks.SKIP : InOtherDecks.KEEP_IMPORTED);
    }

    private static String deckName(ViewContext context, long deckId) {
        return context.decks().activeDecks().stream()
            .filter(deck -> deck.getId() == deckId)
            .map(Deck::getName)
            .findFirst()
            .orElse(tr("add.anotherDeck"));
    }
}
