package com.vocabtrainer.ui;

import com.vocabtrainer.domain.LookupOutcome;
import com.vocabtrainer.service.WordExtras;
import com.vocabtrainer.service.WordExtrasService;

import java.util.Objects;

import static com.vocabtrainer.util.Messages.tr;

/**
 * Fills a {@link WordDetailsCard}'s synonyms, antonyms and play button for the word it shows, from
 * what the lookup cache holds ({@link WordExtrasService#cached}, which sends nothing). The play
 * button downloads and plays the recording; when nothing is known, a link looks the word up online.
 * Both run in the background, are offered only while offline mode is off, and a result that arrives
 * after the card moved on to another word is dropped.
 */
public final class WordExtrasController {
    private final WordDetailsCard card;
    private final ViewContext context;
    private final WordExtrasService extras;
    private final AudioPlayer player;
    private final LatestRequest requests = new LatestRequest();
    /** The word the card shows; null when it shows none. */
    private String english;
    private WordExtras known = WordExtras.NONE;
    /** Whether the word was looked up online since it was shown, so the link is not offered again. */
    private boolean lookedUp;
    private boolean busy;
    private String status = "";

    public WordExtrasController(WordDetailsCard card, ViewContext context, WordExtrasService extras,
                                AudioPlayer player) {
        this.card = card;
        this.context = context;
        this.extras = extras;
        this.player = player;
        card.setOnPlay(this::play);
        card.setOnLookUp(this::lookUp);
        render();
    }

    /**
     * The card shows {@code english} now, or no word when it is null. Showing the same word again
     * changes nothing; another word reads its extras from the cache and drops the work still running
     * for the one before.
     */
    public void show(String english) {
        String word = english == null || english.isBlank() ? null : english.strip();
        if (Objects.equals(word, this.english)) {
            return;
        }
        requests.invalidate();
        this.english = word;
        known = word == null ? WordExtras.NONE : extras.cached(word);
        lookedUp = false;
        busy = false;
        status = "";
        render();
    }

    /** Offline mode may have changed: what is offered follows it. */
    public void settingsChanged() {
        render();
    }

    private void render() {
        if (english == null) {
            card.showExtras(WordExtras.NONE, false, false, false, "");
            return;
        }
        boolean online = !extras.isOffline();
        card.showExtras(known, online, online && known.isEmpty() && !lookedUp && extras.canLookUp(), busy, status);
    }

    private void play() {
        if (english == null || !known.hasAudio() || busy) {
            return;
        }
        String url = known.audioUrl();
        long ticket = requests.next();
        busy = true;
        status = tr("extras.audio.loading");
        render();
        context.async().run(
            () -> extras.audioFile(url),
            file -> {
                if (finished(ticket, "")) {
                    player.play(file, reason -> {
                        if (requests.isLatest(ticket)) {
                            status = tr("extras.audio.failed", reason);
                            render();
                        }
                    });
                }
            },
            error -> finished(ticket, tr("extras.audio.failed", UiErrors.rootMessage(error))));
    }

    private void lookUp() {
        if (english == null || busy) {
            return;
        }
        String word = english;
        long ticket = requests.next();
        busy = true;
        status = tr("extras.lookingUp", word);
        render();
        context.async().run(
            () -> extras.lookUp(word),
            result -> {
                if (requests.isLatest(ticket)) {
                    known = result.extras();
                    lookedUp = true;
                }
                finished(ticket, known.isEmpty() ? nothingFound(word, result) : "");
            },
            error -> finished(ticket, tr("extras.lookUp.failed", UiErrors.rootMessage(error))));
    }

    /** Ends the work of {@code ticket} with {@code message}; false, changing nothing, when the card moved on. */
    private boolean finished(long ticket, String message) {
        if (!requests.isLatest(ticket)) {
            return false;
        }
        busy = false;
        status = message;
        render();
        return true;
    }

    private static String nothingFound(String word, WordExtrasService.Lookup result) {
        if (result.outcome() == LookupOutcome.FOUND) {
            return tr("extras.none", word);
        }
        if (result.outcome() == LookupOutcome.NOT_FOUND) {
            return tr("extras.notFound", word);
        }
        return tr("extras.lookUp.failed", result.message());
    }
}
