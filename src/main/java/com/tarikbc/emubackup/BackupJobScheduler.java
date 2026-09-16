package com.tarikbc.emubackup;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

/**
 * Registers and cancels the periodic backup job.
 *
 * <p>The framework {@code JobScheduler} rather than WorkManager, for the reason in
 * {@code ARCHITECTURE.md} section 8: this app has no dependency tree and adding one to schedule a
 * single job would be the largest thing in it.
 */
public final class BackupJobScheduler {

    private BackupJobScheduler() {}

    /** Schedules, reschedules or cancels so that the job matches the settings exactly. */
    public static void apply(Context ctx, Settings s) {
        JobScheduler js = ctx.getSystemService(JobScheduler.class);
        if (js == null) return;

        JobSpec spec = JobSpec.of(s);
        if (spec == null) {
            js.cancel(JobSpec.JOB_ID);
            return;
        }

        JobInfo.Builder b = new JobInfo.Builder(JobSpec.JOB_ID,
                new ComponentName(ctx, BackupJobService.class))
                .setPeriodic(spec.periodMs, spec.flexMs)
                // Survives a reboot, which is the whole point of a schedule on a handheld that
                // spends most of its life switched off.
                .setPersisted(true)
                .setRequiresCharging(spec.requiresCharging)
                .setRequiredNetworkType(spec.requiresUnmetered
                        ? JobInfo.NETWORK_TYPE_UNMETERED : JobInfo.NETWORK_TYPE_ANY)
                // Legal only because setRequiresDeviceIdle is never set; the two throw together.
                // See JobSpec.
                .setBackoffCriteria(JobSpec.BACKOFF_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL);

        // Scheduling with the same id replaces rather than stacks, so this is also the reschedule.
        js.schedule(b.build());
    }

    /** True when a job is currently registered. Used to show the truth rather than the setting. */
    public static boolean isScheduled(Context ctx) {
        JobScheduler js = ctx.getSystemService(JobScheduler.class);
        return js != null && js.getPendingJob(JobSpec.JOB_ID) != null;
    }
}
