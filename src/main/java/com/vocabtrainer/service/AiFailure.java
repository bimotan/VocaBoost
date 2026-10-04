package com.vocabtrainer.service;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;

import static com.vocabtrainer.util.Messages.tr;

/**
 * Why a request to the AI provider failed, sorted into what the learner can do about it. The review
 * note names the category ({@link #reason}); the provider's own error message, which may echo
 * request details, stays in the log and the Test button's status.
 */
public enum AiFailure {
    /** HTTP 401 or 403: the API key was refused. */
    AUTH,
    /** HTTP 404: the endpoint or the model does not exist. */
    NOT_FOUND,
    /** HTTP 429: too many requests, or the account's quota is used up. */
    RATE_LIMITED,
    /** HTTP 5xx: the provider failed. */
    SERVER_ERROR,
    /** Another HTTP status, such as 400 for a parameter the model does not accept. */
    REFUSED,
    /** The provider did not answer in time. */
    TIMEOUT,
    /** The provider could not be reached: no network, an unknown host, a refused connection. */
    NETWORK,
    /** The provider's answer was empty or could not be read. */
    BAD_RESPONSE,
    /** The saved settings cannot be used, e.g. a plain-http base URL with a key; nothing was sent. */
    SETTINGS,
    /** The request was cancelled. */
    INTERRUPTED,
    /** Anything else. */
    OTHER;

    /** The category of an HTTP status the provider answered with (not 2xx). */
    public static AiFailure ofStatus(int status) {
        if (status == 401 || status == 403) {
            return AUTH;
        }
        if (status == 404) {
            return NOT_FOUND;
        }
        if (status == 429) {
            return RATE_LIMITED;
        }
        return status >= 500 && status < 600 ? SERVER_ERROR : REFUSED;
    }

    /** The category of {@code error} and its causes: an {@link AiRequestException}'s own, or the I/O failure under it. */
    public static AiFailure of(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof AiRequestException request) {
                return request.failure();
            }
            if (cause instanceof HttpTimeoutException && !(cause instanceof HttpConnectTimeoutException)) {
                return TIMEOUT;
            }
            if (cause instanceof HttpConnectTimeoutException || cause instanceof ConnectException
                || cause instanceof UnknownHostException || cause instanceof UnresolvedAddressException) {
                return NETWORK;
            }
            if (cause instanceof InterruptedException) {
                return INTERRUPTED;
            }
        }
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException) {
                return NETWORK;
            }
        }
        return OTHER;
    }

    /**
     * What went wrong in a few words, for the note under a review explanation, e.g. "the API key was
     * refused (HTTP 401)". {@code status} is the HTTP status, or 0 when there was none. It never
     * holds the provider's own message, so it cannot show the key.
     */
    public String reason(int status) {
        String code = String.valueOf(status);
        return switch (this) {
            case AUTH -> tr("ai.failure.auth", code);
            case NOT_FOUND -> tr("ai.failure.notFound", code);
            case RATE_LIMITED -> tr("ai.failure.rateLimited", code);
            case SERVER_ERROR -> tr("ai.failure.serverError", code);
            case REFUSED -> tr("ai.failure.refused", code);
            case TIMEOUT -> tr("ai.failure.timeout");
            case NETWORK -> tr("ai.failure.network");
            case BAD_RESPONSE -> tr("ai.failure.badResponse");
            case SETTINGS -> tr("ai.failure.settings");
            case INTERRUPTED -> tr("ai.failure.interrupted");
            case OTHER -> tr("ai.failure.other");
        };
    }

    /** {@link #reason(int)} of {@code error}, see {@link #of}. */
    public static String reason(Throwable error) {
        int status = 0;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof AiRequestException request) {
                status = request.status();
                break;
            }
        }
        return of(error).reason(status);
    }
}
