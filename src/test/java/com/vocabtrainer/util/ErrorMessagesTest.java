package com.vocabtrainer.util;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErrorMessagesTest {
    @Test
    void causeChainShowsEveryWrappedCauseOutermostFirst() {
        Exception error = new IllegalStateException("无法初始化默认词库",
            new SQLException("[SQLITE_CONSTRAINT_UNIQUE] UNIQUE constraint failed: decks.name"));

        String chain = ErrorMessages.causeChain(error);

        assertEquals("IllegalStateException: 无法初始化默认词库" + System.lineSeparator()
            + "Caused by: SQLException: [SQLITE_CONSTRAINT_UNIQUE] UNIQUE constraint failed: decks.name", chain);
    }

    @Test
    void rootMessagePrefersTheInnermostMessage() {
        Exception error = new IllegalStateException("Cannot save review result",
            new SQLException("[SQLITE_BUSY] database is locked"));

        assertEquals("[SQLITE_BUSY] database is locked", ErrorMessages.rootMessage(error));
    }

    @Test
    void rootMessageFallsBackWhenInnerCauseHasNoMessage() {
        Exception error = new IllegalStateException("Cannot read review words", new NullPointerException());

        assertEquals("Cannot read review words", ErrorMessages.rootMessage(error));
        assertEquals("NullPointerException", ErrorMessages.rootMessage(new NullPointerException()));
        assertEquals("Unknown error", ErrorMessages.rootMessage(null));
    }

    @Test
    void stackTraceIncludesCauses() {
        String trace = ErrorMessages.stackTrace(new IllegalStateException("outer", new SQLException("inner")));

        assertTrue(trace.contains("java.lang.IllegalStateException: outer"));
        assertTrue(trace.contains("Caused by: java.sql.SQLException: inner"));
    }
}
