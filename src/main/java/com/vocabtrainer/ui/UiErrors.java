package com.vocabtrainer.ui;

import com.vocabtrainer.util.AppLogging;
import com.vocabtrainer.util.ErrorMessages;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reports what went wrong in a UI action: the failure is logged and shown in an error dialog
 * instead of being lost on the JavaFX thread.
 */
public final class UiErrors {
    private static final Logger LOGGER = Logger.getLogger(UiErrors.class.getName());

    private final Dialogs dialogs;

    public UiErrors(Dialogs dialogs) {
        this.dialogs = dialogs;
    }

    /** Runs an event-handler body; failures are logged and shown instead of being lost. */
    public void guard(String errorTitle, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            reportFailure(errorTitle, e);
        }
    }

    public void reportFailure(String title, Throwable error) {
        logFailure(title, error);
        if (isInputValidationError(error)) {
            // The message says it all.
            showError(title, error.getMessage());
            return;
        }
        showError(title, rootMessage(error) + System.lineSeparator() + System.lineSeparator()
            + AppLogging.logLocationText());
    }

    /** Logs a failure that the caller shows on its own, e.g. in a status label. */
    public void logFailure(String title, Throwable error) {
        if (isInputValidationError(error)) {
            LOGGER.log(Level.INFO, title + ": " + error.getMessage());
        } else {
            LOGGER.log(Level.WARNING, title, error);
        }
    }

    public void showError(String title, String message) {
        dialogs.showError(title, message == null ? "Unknown error" : message);
    }

    public void showInfo(String message) {
        dialogs.showInfo(message);
    }

    public static String rootMessage(Throwable throwable) {
        return ErrorMessages.rootMessage(throwable);
    }

    private static boolean isInputValidationError(Throwable error) {
        return error instanceof IllegalArgumentException && error.getCause() == null;
    }
}
