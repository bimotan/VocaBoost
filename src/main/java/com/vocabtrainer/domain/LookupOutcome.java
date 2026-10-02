package com.vocabtrainer.domain;

/**
 * How a dictionary lookup ended. {@link #FOUND} and {@link #NOT_FOUND} are answers from the
 * dictionary; every other outcome means the dictionary could not be asked, so the word may well
 * exist and asking again later can give an answer.
 */
public enum LookupOutcome {
    /** The dictionary has the word. */
    FOUND,
    /** The dictionary answered that it does not have the word. */
    NOT_FOUND,
    /** The dictionary could not be reached: no network, an unknown host, a refused or reset connection. */
    NETWORK_ERROR,
    /** The dictionary did not answer in time. */
    TIMEOUT,
    /** The dictionary refused the request (HTTP 401 or 403), for example because of a wrong API key. */
    AUTH_ERROR,
    /** The dictionary limits how often it may be asked and refused this request (HTTP 429). */
    RATE_LIMITED,
    /** The dictionary failed (HTTP 5xx or another unexpected status), or its address is not valid. */
    SERVICE_ERROR,
    /** The dictionary's answer could not be read, such as malformed JSON. */
    BAD_RESPONSE,
    /** The lookup was cancelled before it finished. */
    INTERRUPTED;

    /** True when the dictionary answered (found or not found); false when it could not be asked. */
    public boolean isAnswer() {
        return this == FOUND || this == NOT_FOUND;
    }
}
