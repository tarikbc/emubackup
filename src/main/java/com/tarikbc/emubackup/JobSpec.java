package com.tarikbc.emubackup;

/**
 * The numbers handed to the framework {@code JobScheduler}, worked out where they can be tested.
 *
 * <p>Two platform facts are encoded here rather than discovered on a device:
 *
 * <ul>
 *   <li>A periodic job's period has a floor of 15 minutes. Asking for less does not fail; the
 *       framework silently raises it, which would make a setting quietly mean something else.</li>
 *   <li>{@code setRequiresDeviceIdle(true)} and {@code setBackoffCriteria} are mutually
 *       exclusive and {@code build()} throws if both are set. This app therefore never asks for
 *       idle: a backup that only runs when the device is idle, on a handheld that is either in
 *       use or asleep, is a backup that does not run, and retry backoff is worth more.</li>
 * </ul>
 *
 * <p>Android-free by design; see {@code test.sh}.
 */
public final class JobSpec {

    /** Stable across versions: rescheduling relies on replacing the same id, not stacking jobs. */
    public static final int JOB_ID = 1001;

    public static final long MIN_PERIOD_MS = 15 * 60 * 1000L;

    /** Retry delay after a failure the job asked to retry, doubling from here. */
    public static final long BACKOFF_MS = 30 * 60 * 1000L;

    public final long periodMs;

    /** How late the framework may run it, which is what lets it batch jobs and save power. */
    public final long flexMs;

    public final boolean requiresCharging;
    public final boolean requiresUnmetered;

    private JobSpec(long periodMs, long flexMs, boolean charging, boolean unmetered) {
        this.periodMs = periodMs;
        this.flexMs = flexMs;
        this.requiresCharging = charging;
        this.requiresUnmetered = unmetered;
    }

    /** Null when nothing should be scheduled. */
    public static JobSpec of(Settings s) {
        if (s == null || !s.scheduled()) return null;
        long period = Math.max(MIN_PERIOD_MS, s.frequency.intervalMs);
        // A quarter of the period, which is generous enough for the framework to batch this with
        // whatever else it is waiting to run, and tight enough that "daily" still means daily.
        long flex = Math.max(MIN_PERIOD_MS, period / 4);
        return new JobSpec(period, flex, s.requiresCharging, s.requiresUnmetered);
    }
}
