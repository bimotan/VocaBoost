package com.vocabtrainer.ui;

import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Runs slow work off the UI thread and delivers its outcome back on the UI thread. {@link UiAsync}
 * is the JavaFX implementation; code that must stay free of JavaFX, such as the review presenter,
 * depends only on this interface.
 */
public interface TaskRunner {
    <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure);
}
