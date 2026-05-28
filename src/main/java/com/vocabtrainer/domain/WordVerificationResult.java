package com.vocabtrainer.domain;

public record WordVerificationResult(
    boolean found,
    String source,
    String message
) {
    public static WordVerificationResult found(String source, String message) {
        return new WordVerificationResult(true, source, message);
    }

    public static WordVerificationResult missing(String message) {
        return new WordVerificationResult(false, "", message);
    }
}
