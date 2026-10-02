package com.vocabtrainer.ui;

/**
 * Tickets for requests that can overlap, such as a dictionary lookup started while an older one
 * is still running. Only the newest ticket is current, so a late result of an older request can
 * be recognised and dropped instead of overwriting newer content.
 */
public final class LatestRequest {
    private long latest;

    /** Starts a request; every ticket handed out before becomes stale. */
    public long next() {
        return ++latest;
    }

    /** Makes every ticket handed out so far stale, e.g. when the content they were for is gone. */
    public void invalidate() {
        latest++;
    }

    public boolean isLatest(long ticket) {
        return ticket == latest;
    }
}
