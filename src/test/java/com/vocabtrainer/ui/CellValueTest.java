package com.vocabtrainer.ui;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CellValueTest {
    @Test
    void numbersSortByValueAndShowTheirText() {
        List<CellValue<Integer>> intervals = new ArrayList<>(List.of(
            new CellValue<>(10, "10 days"), new CellValue<>(2, "2 days"), new CellValue<>(14, "14 days"),
            new CellValue<>(null, "-"), new CellValue<>(3, "3 days"), new CellValue<>(1, "1 day")));
        Collections.sort(intervals);
        assertEquals(List.of("-", "1 day", "2 days", "3 days", "10 days", "14 days"), texts(intervals));

        List<CellValue<Double>> memory = new ArrayList<>(List.of(
            new CellValue<>(1.0, "100%"), new CellValue<>(0.45, "45%"), new CellValue<>(0.09, "9%"),
            new CellValue<>(null, "New")));
        Collections.sort(memory);
        assertEquals(List.of("New", "9%", "45%", "100%"), texts(memory));
        memory.sort(Collections.reverseOrder());
        assertEquals(List.of("100%", "45%", "9%", "New"), texts(memory), "descending puts the cells without a key last");
    }

    @Test
    void datesSortByTimeAndCellsWithoutADateFirst() {
        LocalDateTime noon = LocalDateTime.of(2026, 5, 28, 12, 0);
        List<CellValue<LocalDateTime>> dates = new ArrayList<>(List.of(
            new CellValue<>(noon.plusDays(1), "tomorrow"), new CellValue<>(null, "-"), new CellValue<>(noon, "today")));
        Collections.sort(dates);
        assertEquals(List.of("-", "today", "tomorrow"), texts(dates));
        assertEquals("today", String.valueOf(dates.get(1)), "a cell shows the text");
        assertEquals(0, new CellValue<Integer>(null, "a").compareTo(new CellValue<>(null, "b")));
    }

    private static List<String> texts(List<? extends CellValue<?>> values) {
        return values.stream().map(CellValue::text).toList();
    }
}
