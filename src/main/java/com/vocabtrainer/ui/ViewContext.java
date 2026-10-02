package com.vocabtrainer.ui;

import javafx.stage.Window;

import java.util.function.Supplier;

/**
 * What every part of the main window shares: dialogs, error reporting, background work, change
 * notifications and the current deck.
 *
 * @param window the main window, as owner of file choosers
 */
public record ViewContext(
    Dialogs dialogs,
    UiErrors errors,
    UiAsync async,
    DataChanges changes,
    DeckContext decks,
    Supplier<Window> window
) {
}
