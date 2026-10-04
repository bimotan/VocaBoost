package com.vocabtrainer.ui;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Background work that holds the database's write lock for a long time, such as restoring a JSON
 * backup, which is one transaction so that it is all or nothing. While one runs, a rating would wait
 * for the lock and could fail after the busy timeout, so the Review tab pauses its ratings and says
 * why ({@link #noteProperty()}). Work that writes in short transactions, such as building a deck from
 * ECDICT, needs no pause. Use it on the JavaFX thread.
 */
public final class LongWrites {
    private final ReadOnlyObjectWrapper<String> note = new ReadOnlyObjectWrapper<>(this, "note");
    private final List<Running> running = new ArrayList<>();

    /**
     * Why the views that write are paused, the note of the long write that started first; null
     * while none runs.
     */
    public ReadOnlyObjectProperty<String> noteProperty() {
        return note.getReadOnlyProperty();
    }

    public boolean isRunning() {
        return !running.isEmpty();
    }

    /**
     * Starts a long write; {@code why} is shown where writing is paused. Ending the returned write,
     * which may be done more than once, lets the views write again once no other long write runs.
     */
    public Running begin(String why) {
        Running write = new Running(why);
        running.add(write);
        update();
        return write;
    }

    private void update() {
        note.set(running.isEmpty() ? null : running.get(0).why);
    }

    /** One long write that {@link #begin} started. */
    public final class Running {
        private final String why;

        private Running(String why) {
            this.why = why;
        }

        /** Ends this long write; does nothing when it has ended already. */
        public void end() {
            if (running.remove(this)) {
                update();
            }
        }
    }
}
