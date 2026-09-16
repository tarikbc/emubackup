package com.tarikbc.emubackup;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * A moment, the way a person names it: "Today, 14:24", "Yesterday, 12:00", "Tue 1 Sep, 12:00".
 *
 * <p>Version ids carry the same information as {@code v0008-20260916-1724} and nobody should
 * have to read that. Time of day follows the locale's short form, so a device set to 12-hour
 * time sees 12-hour time. Android-free; see {@code test.sh}.
 */
public final class When {

    private When() {}

    public static String format(long atMs, long nowMs) {
        return format(atMs, nowMs, TimeZone.getDefault(), Locale.getDefault());
    }

    public static String format(long atMs, long nowMs, TimeZone tz, Locale locale) {
        DateFormat time = DateFormat.getTimeInstance(DateFormat.SHORT, locale);
        time.setTimeZone(tz);
        String clock = time.format(new Date(atMs));

        Calendar at = Calendar.getInstance(tz, locale);
        at.setTimeInMillis(atMs);
        Calendar now = Calendar.getInstance(tz, locale);
        now.setTimeInMillis(nowMs);

        if (sameDay(at, now)) return "Today, " + clock;
        now.add(Calendar.DAY_OF_YEAR, -1);
        if (sameDay(at, now)) return "Yesterday, " + clock;
        now.add(Calendar.DAY_OF_YEAR, 1);

        String pattern = at.get(Calendar.YEAR) == now.get(Calendar.YEAR) ? "EEE d MMM" : "d MMM yyyy";
        SimpleDateFormat day = new SimpleDateFormat(pattern, locale);
        day.setTimeZone(tz);
        return day.format(new Date(atMs)) + ", " + clock;
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }
}
