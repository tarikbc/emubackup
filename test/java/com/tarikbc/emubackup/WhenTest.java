package com.tarikbc.emubackup;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.Locale;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

class WhenTest {

    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    private static final long NOW = Instant.parse("2026-09-16T14:31:00Z").toEpochMilli();

    private static String at(String iso) {
        return When.format(Instant.parse(iso).toEpochMilli(), NOW, UTC, Locale.US);
    }

    @Test void today() {
        assertEquals("Today, 12:00 PM", at("2026-09-16T12:00:00Z"));
    }

    @Test void yesterdayIsByCalendarNotByHours() {
        // 23:50 the day before is "yesterday" even though it is under 15 hours ago.
        assertEquals("Yesterday, 11:50 PM", at("2026-09-15T23:50:00Z"));
    }

    @Test void thisYearNamesTheWeekday() {
        assertEquals("Tue 1 Sep, 12:00 PM", at("2026-09-01T12:00:00Z"));
    }

    @Test void anotherYearNamesTheYear() {
        assertEquals("25 Dec 2025, 12:00 PM", at("2025-12-25T12:00:00Z"));
    }

    @Test void twelveHourFollowsTheLocale() {
        assertEquals("Today, 12:00",
                When.format(Instant.parse("2026-09-16T12:00:00Z").toEpochMilli(), NOW, UTC, Locale.UK));
    }
}
