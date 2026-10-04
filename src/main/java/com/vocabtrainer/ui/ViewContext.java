package com.vocabtrainer.ui;

import javafx.stage.Window;

import java.util.function.Supplier;

/**
 * What every part of the main window shares: dialogs, error reporting, background work, change
 * notifications, the current deck and the long writes that pause the Review tab.
 *
 * @param window     the main window, as owner of file choosers
 * @param longWrites background work that holds the database's write lock for long, such as a backup restore
 */
public record ViewContext(
    Dialogs dialogs,
    UiErrors errors,
    UiAsync async,
    DataChanges changes,
    DeckContext decks,
    Supplier<Window> window,
    LongWrites longWrites
) {
}
