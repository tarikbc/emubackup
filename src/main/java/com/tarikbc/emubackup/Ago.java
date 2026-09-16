package com.tarikbc.emubackup;

/**
 * "2 hours ago", for people. Numbers beat adjectives, and a person reading a status line wants
 * the one unit that matters, not a timestamp to do arithmetic on.
 *
 * <p>Android-free by design; see {@code test.sh}.
 */
public final class Ago {

    private Ago() {}

    private static final long MIN = 60_000L, HOUR = 60 * MIN, DAY = 24 * HOUR;

    /** Relative, coarse, never negative. {@code atMs <= 0} reads as "never". */
    public static String format(long atMs, long nowMs) {
        if (atMs <= 0) return "never";
        long d = Math.max(0, nowMs - atMs);
        if (d < MIN) return "just now";
        if (d < HOUR) return plural(d / MIN, "minute") + " ago";
        if (d < DAY) return plural(d / HOUR, "hour") + " ago";
        if (d < 30 * DAY) return plural(d / DAY, "day") + " ago";
        if (d < 365 * DAY) return plural(d / (30 * DAY), "month") + " ago";
        return plural(d / (365 * DAY), "year") + " ago";
    }

    private static String plural(long n, String unit) {
        return n + " " + unit + (n == 1 ? "" : "s");
    }
}
