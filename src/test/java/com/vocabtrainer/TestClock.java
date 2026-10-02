package com.vocabtrainer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock in UTC that a test sets and moves forward; safe to read from another thread. */
public final class TestClock extends Clock {
    private volatile Instant now;

    public TestClock(LocalDateTime start) {
        set(start);
    }

    public void set(LocalDateTime time) {
        now = time.toInstant(ZoneOffset.UTC);
    }

    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    public LocalDateTime now() {
        return LocalDateTime.ofInstant(now, ZoneOffset.UTC);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Instant instant() {
        return now;
    }
}
