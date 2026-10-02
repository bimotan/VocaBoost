package com.vocabtrainer.service;

/** How far an import is with looking up the meanings its file does not have. */
public record ImportProgress(int lookedUp, int toLookUp) {
    public String toDisplayText() {
        return "Looking up meanings: " + lookedUp + " of " + toLookUp;
    }
}
