package com.vocabtrainer.ui;

import com.vocabtrainer.util.AppLogging;
import com.vocabtrainer.util.ErrorMessages;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Region;

import java.util.logging.Level;
import java.util.logging.Logger;

import static com.vocabtrainer.util.Messages.tr;

/** Error alerts shared by startup, the uncaught-exception handler and the main window. */
public final class ErrorDialogs {
    private static final Logger LOGGER = Logger.getLogger(ErrorDialogs.class.getName());

    // Only read and written on the JavaFX Application Thread.
    private static boolean uncaughtAlertShowing;

    private ErrorDialogs() {
    }

    /**
     * Call on the JavaFX Application Thread once the toolkit is running. Uncaught exceptions on
     * any thread, including JavaFX event handlers, are then logged and shown in an error alert.
     */
    public static void installUncaughtExceptionHandler() {
        Thread.UncaughtExceptionHandler handler = ErrorDialogs::handleUncaught;
        Thread.setDefaultUncaughtExceptionHandler(handler);
        Thread.currentThread().setUncaughtExceptionHandler(handler);
    }

    /** An error alert with the cause chain, the log folder and the full stack trace as details. */
    public static Alert exceptionAlert(String title, String header, Throwable error) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(ErrorMessages.causeChain(error) + System.lineSeparator() + System.lineSeparator()
            + AppLogging.logLocationText());
        TextArea details = new TextArea(ErrorMessages.stackTrace(error));
        details.setEditable(false);
        details.setPrefRowCount(12);
        alert.getDialogPane().setExpandableContent(details);
        alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        alert.setResizable(true);
        return alert;
    }

    private static void handleUncaught(Thread thread, Throwable error) {
        AppLogging.logUncaught(thread, error);
        try {
            // Deferred so the alert never opens in the middle of a layout pass or another event.
            Platform.runLater(() -> showUncaughtAlert(error));
        } catch (IllegalStateException toolkitStopped) {
            // JavaFX has already shut down; the error is in the log.
        }
    }

    private static void showUncaughtAlert(Throwable error) {
        if (uncaughtAlertShowing) {
            // One alert at a time: a broken layout pass can throw on every pulse.
            return;
        }
        try {
            Alert alert = exceptionAlert(tr("error.unexpected.title"), tr("error.unexpected.header"), error);
            alert.setOnHidden(event -> uncaughtAlertShowing = false);
            uncaughtAlertShowing = true;
            alert.show();
        } catch (RuntimeException e) {
            uncaughtAlertShowing = false;
            LOGGER.log(Level.WARNING, "Cannot show the error dialog", e);
        }
    }
}
