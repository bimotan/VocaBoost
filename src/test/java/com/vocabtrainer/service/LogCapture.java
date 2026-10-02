package com.vocabtrainer.service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Collects the records a class logs during a test and keeps them off the console. */
final class LogCapture extends Handler implements AutoCloseable {
    private final Logger logger;
    private final boolean previousUseParentHandlers;
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();

    private LogCapture(Logger logger) {
        this.logger = logger;
        this.previousUseParentHandlers = logger.getUseParentHandlers();
        setLevel(Level.ALL);
        logger.addHandler(this);
        logger.setUseParentHandlers(false);
    }

    static LogCapture of(Class<?> type) {
        return new LogCapture(Logger.getLogger(type.getName()));
    }

    List<LogRecord> warnings() {
        return records.stream().filter(record -> record.getLevel() == Level.WARNING).toList();
    }

    @Override
    public void publish(LogRecord record) {
        records.add(record);
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
        logger.removeHandler(this);
        logger.setUseParentHandlers(previousUseParentHandlers);
    }
}
