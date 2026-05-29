package com.vocabtrainer.domain;

public record WordVerificationResult(
    VerificationStatus status,
    String source,
    String message
) {
    public static WordVerificationResult found(String source, String message) {
        return new WordVerificationResult(VerificationStatus.VERIFIED, source, message);
    }

    public static WordVerificationResult missing(String message) {
        return new WordVerificationResult(VerificationStatus.UNVERIFIED, "", message);
    }

    public static WordVerificationResult invalid(String message) {
        return new WordVerificationResult(VerificationStatus.INVALID, "", message);
    }

    public boolean found() {
        return status == VerificationStatus.VERIFIED;
    }
}
