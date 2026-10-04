package com.vocabtrainer.ui.importing;

import com.vocabtrainer.domain.LookupOutcome;
import javafx.scene.control.ProgressIndicator;

import static com.vocabtrainer.util.Messages.tr;

/** What the add form and the lookup box show about a dictionary lookup that found nothing. */
final class LookupMessages {
    private LookupMessages() {
    }

    /**
     * One line that tells "the dictionaries do not have the word" apart from "the dictionaries
     * could not be asked", and says what the user can do about the latter.
     */
    static String headline(LookupOutcome outcome) {
        return switch (outcome) {
            case FOUND -> "";
            case NOT_FOUND -> tr("lookup.outcome.notFound");
            case NETWORK_ERROR -> tr("lookup.outcome.networkError");
            case TIMEOUT -> tr("lookup.outcome.timeout");
            case AUTH_ERROR -> tr("lookup.outcome.authError");
            case RATE_LIMITED -> tr("lookup.outcome.rateLimited");
            case SERVICE_ERROR -> tr("lookup.outcome.serviceError");
            case BAD_RESPONSE -> tr("lookup.outcome.badResponse");
            case INTERRUPTED -> tr("lookup.outcome.interrupted");
            case OFFLINE -> tr("lookup.outcome.offline");
        };
    }

    /** A small spinning indicator, hidden until a lookup runs (see {@link #setBusy}). */
    static ProgressIndicator busyIndicator(String id) {
        ProgressIndicator indicator = new ProgressIndicator(0);
        indicator.setId(id);
        indicator.setPrefSize(20, 20);
        indicator.setMaxSize(20, 20);
        indicator.setVisible(false);
        return indicator;
    }

    /** Shows the indicator spinning, or hides it and stops its animation. */
    static void setBusy(ProgressIndicator indicator, boolean busy) {
        indicator.setProgress(busy ? ProgressIndicator.INDETERMINATE_PROGRESS : 0);
        indicator.setVisible(busy);
    }
}
