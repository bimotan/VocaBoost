package com.vocabtrainer.ui;

/**
 * A table cell's value that shows {@code text} and sorts by {@code key}, so that numbers and dates
 * sort by value ("2 days" before "10 days", "45%" before "100%") and not as text. A cell without a
 * key, such as a new word's memory or a deck that was never reviewed, sorts before every other one.
 * A table column shows the text, since the default cell shows a value's {@code toString()}.
 */
public record CellValue<K extends Comparable<? super K>>(K key, String text) implements Comparable<CellValue<K>> {
    @Override
    public int compareTo(CellValue<K> other) {
        if (key == null || other.key == null) {
            return Boolean.compare(key != null, other.key != null);
        }
        return key.compareTo(other.key);
    }

    @Override
    public String toString() {
        return text;
    }
}
