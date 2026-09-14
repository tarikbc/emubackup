package com.tarikbc.emubackup;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * A backup version's identity: {@code v0007-20260914-1432}.
 *
 * <p>The counter is authoritative for ordering and the timestamp is for humans. Sorting by
 * counter rather than by time matters because two runs inside the same minute, or a device
 * whose clock moves backwards, must still produce an unambiguous chain order.
 *
 * <p>See {@code FORMAT.md} section 2.
 */
public final class VersionId implements Comparable<VersionId> {

    public final int counter;
    public final String stamp;

    private VersionId(int counter, String stamp) {
        this.counter = counter;
        this.stamp = stamp;
    }

    /** Builds the next id after {@code previousCounter}, stamped with the given UTC epoch. */
    public static VersionId next(int previousCounter, long epochMs) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
        c.setTimeInMillis(epochMs);
        String stamp = String.format(Locale.ROOT, "%04d%02d%02d-%02d%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
        return new VersionId(previousCounter + 1, stamp);
    }

    public static VersionId parse(String s) {
        if (s == null || s.length() < 7 || s.charAt(0) != 'v') {
            throw new IllegalArgumentException("not a version id: " + s);
        }
        int dash = s.indexOf('-');
        if (dash < 2) throw new IllegalArgumentException("not a version id: " + s);
        try {
            return new VersionId(Integer.parseInt(s.substring(1, dash)), s.substring(dash + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a version id: " + s, e);
        }
    }

    public static boolean looksLikeOne(String s) {
        try {
            parse(s);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public String id() {
        return String.format(Locale.ROOT, "v%04d-%s", counter, stamp);
    }

    @Override public int compareTo(VersionId o) {
        return Integer.compare(counter, o.counter);
    }

    @Override public boolean equals(Object o) {
        return o instanceof VersionId && ((VersionId) o).id().equals(id());
    }

    @Override public int hashCode() {
        return id().hashCode();
    }

    @Override public String toString() {
        return id();
    }
}
