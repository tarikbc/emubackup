package com.tarikbc.emubackup;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AgoTest {
    private static final long M = 60_000L, H = 60 * M, D = 24 * H;

    @Test void units() {
        long now = 1_000_000_000_000L;
        assertEquals("never", Ago.format(0, now));
        assertEquals("just now", Ago.format(now - 30_000, now));
        assertEquals("1 minute ago", Ago.format(now - M, now));
        assertEquals("5 minutes ago", Ago.format(now - 5 * M, now));
        assertEquals("2 hours ago", Ago.format(now - 2 * H, now));
        assertEquals("1 day ago", Ago.format(now - D - H, now));
        assertEquals("3 months ago", Ago.format(now - 95 * D, now));
        assertEquals("1 year ago", Ago.format(now - 400 * D, now));
        // A clock that went backwards must not produce "-3 minutes ago".
        assertEquals("just now", Ago.format(now + 5 * M, now));
    }
}
