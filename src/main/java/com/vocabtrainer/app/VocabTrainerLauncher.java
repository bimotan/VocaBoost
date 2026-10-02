package com.vocabtrainer.app;

import com.vocabtrainer.util.AppLogging;

public final class VocabTrainerLauncher {
    private VocabTrainerLauncher() {
    }

    public static void main(String[] args) {
        // Before JavaFX loads, so a failure to start the toolkit still ends up in the log file.
        AppLogging.initialize();
        AppLogging.installUncaughtExceptionLogger();
        VocabTrainerApp.main(args);
    }
}
