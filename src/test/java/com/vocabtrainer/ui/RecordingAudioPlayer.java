package com.vocabtrainer.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Plays nothing: records the files it was asked to play, or reports {@link #failure} for them. */
final class RecordingAudioPlayer implements AudioPlayer {
    final List<Path> played = new CopyOnWriteArrayList<>();
    /** When set, every file "cannot be played" with this reason. */
    volatile String failure;

    @Override
    public void play(Path file, Consumer<String> onError) {
        played.add(file);
        if (failure != null) {
            onError.accept(failure);
        }
    }
}
