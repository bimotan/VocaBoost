package com.vocabtrainer.ui;

import javafx.stage.Window;

import java.time.Clock;
import java.util.function.Supplier;

/**
 * What every part of the main window shares: dialogs, error reporting, background work, change
 * notifications, the current deck, the long writes that pause the Review tab and the clock.
 *
 * @param window     the main window, as owner of file choosers
 * @param longWrites background work that holds the database's write lock for long, such as a backup restore
 * @param clock      the time of the services, which also dates the words the views add
 */
public record ViewContext(
    Dialogs dialogs,
    UiErrors errors,
    UiAsync async,
    DataChanges changes,
    DeckContext decks,
    Supplier<Window> window,
    LongWrites longWrites,
    Clock clock
) {
    /** A context on the system clock with its own long-write tracker. */
    public ViewContext(Dialogs dialogs, UiErrors errors, UiAsync async, DataChanges changes, DeckContext decks,
                       Supplier<Window> window) {
        this(dialogs, errors, async, changes, decks, window, new LongWrites(), Clock.systemDefaultZone());
    }
}
