package com.tarikbc.emubackup;

/**
 * Byte-count formatting for the UI.
 *
 * <p>Deliberately android-free so it runs under plain JVM JUnit (see {@code test.sh}).
 *
 * <p>Uses 1024-based units with the short labels {@code B/KB/MB/GB/TB}, matching what
 * {@code du -h} reports on the device. That consistency matters: the figures in
 * {@code docs/PROVENANCE.md} were captured with {@code du -sh}, so a decimal formatter
 * here would make the app disagree with its own provenance record for no benefit.
 *
 * <p>One significant decimal is shown below 10 units and none at or above it, so sizes
 * stay short and column-aligned in a list: {@code 663 KB}, {@code 7.9 MB}, {@code 143 MB}.
 *
 * <p>See {@code DESIGN.md §4} (type scale and numeric alignment).
 */
public final class Sizes {

    private static final String[] UNITS = { "B", "KB", "MB", "GB", "TB" };

    /**
     * Formats a byte count for display.
     *
     * @param bytes a non-negative byte count
     * @return e.g. {@code "0 B"}, {@code "512 B"}, {@code "7.9 MB"}, {@code "143 MB"}
     * @throws IllegalArgumentException if {@code bytes} is negative. A negative size is
     *         always a bug upstream (a bad subtraction in a diff, usually), and silently
     *         rendering it as "0 B" would hide that. For a signed delta use {@link #delta}.
     */
    public static String human(long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("negative byte count: " + bytes);
        if (bytes < 1024) return bytes + " B";

        double v = bytes;
        int unit = 0;
        // Step up while the value would still render as >= 1024 in the current unit, and
        // stop at TB so an absurd input degrades to a large TB figure rather than running
        // off the end of UNITS.
        while (v >= 1024 && unit < UNITS.length - 1) {
            v /= 1024;
            unit++;
        }
        // Rounding can push 1023.7 KB to "1024 KB", which reads as wrong. Promote it.
        if (v >= 1023.95 && unit < UNITS.length - 1) {
            v /= 1024;
            unit++;
        }
        return (v < 10 ? trimZero(String.format(java.util.Locale.ROOT, "%.1f", v))
                       : String.format(java.util.Locale.ROOT, "%.0f", v))
                + " " + UNITS[unit];
    }

    /** Formats a signed difference, e.g. {@code "+2.1 MB"} or {@code "-512 B"}. Zero is {@code "0 B"}. */
    public static String delta(long bytes) {
        if (bytes == 0) return "0 B";
        return (bytes > 0 ? "+" : "-") + human(Math.abs(bytes));
    }

    /** {@code "1.0 MB"} reads worse than {@code "1 MB"} in a dense list, so drop a trailing {@code .0}. */
    private static String trimZero(String s) {
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    private Sizes() {}
}
