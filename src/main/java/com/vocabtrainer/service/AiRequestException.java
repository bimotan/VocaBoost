package com.vocabtrainer.service;

/**
 * A request to the AI provider that failed, with its category ({@link AiFailure}) and HTTP status.
 * The message is for the AI settings' Test button and the log; it never contains the API key.
 */
public class AiRequestException extends IllegalStateException {
    private final AiFailure failure;
    private final int status;

    public AiRequestException(AiFailure failure, String message) {
        this(failure, 0, message, null);
    }

    public AiRequestException(AiFailure failure, String message, Throwable cause) {
        this(failure, 0, message, cause);
    }

    /** @param status the HTTP status the provider answered with; 0 when there was none */
    public AiRequestException(AiFailure failure, int status, String message, Throwable cause) {
        super(message, cause);
        this.failure = failure == null ? AiFailure.OTHER : failure;
        this.status = status;
    }

    public AiFailure failure() {
        return failure;
    }

    /** The HTTP status the provider answered with; 0 when there was none. */
    public int status() {
        return status;
    }
}
