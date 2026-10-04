package com.vocabtrainer.ui;

import javafx.scene.media.Media;
import javafx.scene.media.MediaException;
import javafx.scene.media.MediaPlayer;

import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.vocabtrainer.util.Messages.tr;

/**
 * Plays recordings with JavaFX Media. On Linux, JavaFX decodes mp3 with the system's libavcodec
 * (FFmpeg); without it a recording cannot be played, which is reported rather than thrown, as is
 * any other media failure.
 */
public final class JavaFxAudioPlayer implements AudioPlayer {
    private static final Logger LOGGER = Logger.getLogger(JavaFxAudioPlayer.class.getName());

    private MediaPlayer playing;

    @Override
    public void play(Path file, Consumer<String> onError) {
        stop();
        MediaPlayer player;
        try {
            player = new MediaPlayer(new Media(file.toUri().toString()));
        } catch (MediaException | UnsupportedOperationException | LinkageError e) {
            LOGGER.log(Level.WARNING, "Cannot play " + file, e);
            onError.accept(reason(e));
            return;
        }
        player.setOnError(() -> {
            LOGGER.log(Level.WARNING, "Cannot play " + file, player.getError());
            onError.accept(reason(player.getError()));
            player.dispose();
        });
        player.setOnEndOfMedia(player::dispose);
        playing = player;
        player.play();
    }

    private void stop() {
        if (playing != null) {
            playing.dispose();
            playing = null;
        }
    }

    private static String reason(Throwable error) {
        String message = error == null ? "" : UiErrors.rootMessage(error);
        return message.isBlank() ? tr("extras.audio.cannotPlay") : message;
    }
}
