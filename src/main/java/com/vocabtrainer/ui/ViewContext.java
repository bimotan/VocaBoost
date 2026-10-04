package com.vocabtrainer.ui;

import javafx.stage.Window;

import java.time.Clock;
import java.util.function.Supplier;

/**
 * What every part of the main window shares: dialogs, error reporting, background work, change
 * notifications, the current deck and the clock.
 *
 * @param window the main window, as owner of file choosers
 * @param clock  the time of the services, which also dates the words the views add
 */
public record ViewContext(
    Dialogs dialogs,
    UiErrors errors,
    UiAsync async,
    DataChanges changes,
    DeckContext decks,
    Supplier<Window> window,
    Clock clock
) {
    /** A context on the system clock. */
    public ViewContext(Dialogs dialogs, UiErrors errors, UiAsync async, DataChanges changes, DeckContext decks,
                       Supplier<Window> window) {
        this(dialogs, errors, async, changes, decks, window, Clock.systemDefaultZone());
    }
}
