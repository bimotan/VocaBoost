package com.vocabtrainer.ui;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Tells the views which kinds of data an action changed, so each view can decide whether what it
 * shows is out of date. Whoever changes data publishes; nobody refreshes other views directly.
 */
public final class DataChanges {
    private final List<Consumer<Set<DataChange>>> listeners = new CopyOnWriteArrayList<>();

    public void subscribe(Consumer<Set<DataChange>> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Notifies every listener. A listener that fails does not keep the others from being told; the
     * first failure is rethrown once all of them ran.
     */
    public void publish(DataChange first, DataChange... more) {
        Set<DataChange> changes = Collections.unmodifiableSet(EnumSet.of(first, more));
        Listeners.notifyAll(listeners, listener -> listener.accept(changes));
    }
}
