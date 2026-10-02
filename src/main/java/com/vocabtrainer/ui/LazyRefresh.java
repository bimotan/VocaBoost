package com.vocabtrainer.ui;

import javafx.scene.control.Tab;

/**
 * Recomputes a tab's content only while the tab is visible. A data change or deck switch marks the
 * content stale; a stale tab is recomputed as soon as it is selected, or right away when it is the
 * tab on screen. Hidden tabs do no work, however many changes happen meanwhile.
 */
public final class LazyRefresh {
    private final Tab tab;
    private final Runnable refresh;
    private final boolean onEverySelection;
    private boolean stale = true;

    /**
     * @param errorTitle       the title of the error dialog when recomputing on selection fails
     * @param onEverySelection recompute whenever the tab is selected, even if no data changed;
     *                         for content that depends on the clock
     */
    public LazyRefresh(Tab tab, Runnable refresh, UiErrors errors, String errorTitle, boolean onEverySelection) {
        this.tab = tab;
        this.refresh = refresh;
        this.onEverySelection = onEverySelection;
        tab.selectedProperty().addListener((observable, wasSelected, selected) -> {
            if (selected && (stale || onEverySelection)) {
                errors.guard(errorTitle, this::refreshNow);
            }
        });
    }

    /**
     * The content is out of date. It is recomputed now if the tab is showing, otherwise when the tab
     * is next selected. A failure is thrown to the caller, which reports it.
     */
    public void markStale() {
        stale = true;
        if (tab.isSelected()) {
            refreshNow();
        }
    }

    /** Recomputes the content now, e.g. for a Refresh button or a changed filter. */
    public void refreshNow() {
        stale = false;
        try {
            refresh.run();
        } catch (RuntimeException e) {
            stale = true;
            throw e;
        }
    }
}
