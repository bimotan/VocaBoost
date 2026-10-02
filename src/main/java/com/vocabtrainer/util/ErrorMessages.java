package com.vocabtrainer.util;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Turns exceptions into text for error dialogs and log files. */
public final class ErrorMessages {
    private static final int MAX_CHAIN_LENGTH = 10;

    private ErrorMessages() {
    }

    /** The message closest to the root cause, e.g. the SQLite error behind a service wrapper. */
    public static String rootMessage(Throwable error) {
        if (error == null) {
            return "Unknown error";
        }
        List<Throwable> chain = chain(error);
        for (int i = chain.size() - 1; i >= 0; i--) {
            String message = chain.get(i).getMessage();
            if (message != null && !message.isBlank()) {
                return message;
            }
        }
        return chain.get(chain.size() - 1).getClass().getSimpleName();
    }

    /** One line per exception in the cause chain, outermost first. */
    public static String causeChain(Throwable error) {
        if (error == null) {
            return "Unknown error";
        }
        StringBuilder builder = new StringBuilder();
        for (Throwable current : chain(error)) {
            if (builder.length() > 0) {
                builder.append(System.lineSeparator()).append("Caused by: ");
            }
            builder.append(current.getClass().getSimpleName());
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                builder.append(": ").append(current.getMessage());
            }
        }
        return builder.toString();
    }

    public static String stackTrace(Throwable error) {
        if (error == null) {
            return "";
        }
        StringWriter writer = new StringWriter();
        try (PrintWriter printer = new PrintWriter(writer)) {
            error.printStackTrace(printer);
        }
        return writer.toString();
    }

    private static List<Throwable> chain(Throwable error) {
        List<Throwable> chain = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = error;
        while (current != null && seen.add(current) && chain.size() < MAX_CHAIN_LENGTH) {
            chain.add(current);
            current = current.getCause();
        }
        return chain;
    }
}
