package com.vocabtrainer.ui;

import java.util.function.Consumer;

/** Calls every listener even if one fails, then rethrows the first failure. */
final class Listeners {
    private Listeners() {
    }

    static <L> void notifyAll(Iterable<L> listeners, Consumer<L> call) {
        RuntimeException failure = null;
        for (L listener : listeners) {
            try {
                call.accept(listener);
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
