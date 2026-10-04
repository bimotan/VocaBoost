package com.vocabtrainer.service;

import static com.vocabtrainer.util.Messages.tr;

/** How far an import is with looking up the meanings its file does not have. */
public record ImportProgress(int lookedUp, int toLookUp) {
    public String toDisplayText() {
        return tr("import.progress", lookedUp, toLookUp);
    }
}
