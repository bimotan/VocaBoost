package com.vocabtrainer.ui;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Plays a downloaded pronunciation recording. The app uses {@link JavaFxAudioPlayer}; the UI tests
 * use a fake that only records what it was asked to play.
 */
public interface AudioPlayer {
    /**
     * Starts playing {@code file}, stopping a recording still playing; call on the JavaFX thread.
     *
     * @param onError called on the JavaFX thread with the reason when the file cannot be played
     */
    void play(Path file, Consumer<String> onError);
}
