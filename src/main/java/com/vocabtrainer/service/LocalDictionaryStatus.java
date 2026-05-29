package com.vocabtrainer.service;

public record LocalDictionaryStatus(
    int loadedCount,
    int skippedRows,
    String primarySource,
    String configuredPath,
    boolean configuredPathLoaded,
    boolean bundledStarterLoaded
) {
    public String toDisplayText() {
        String source = primarySource == null || primarySource.isBlank() ? "none" : primarySource;
        return "Loaded entries: " + loadedCount
            + " | Skipped rows: " + skippedRows
            + " | Source: " + source;
    }
}
