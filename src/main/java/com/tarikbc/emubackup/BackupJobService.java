package com.tarikbc.emubackup;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Environment;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The scheduled backup.
 *
 * <p>The work happens inside the job rather than in {@link BackupService}. A foreground service
 * would give unbounded runtime, but Android 12 forbids starting one from the background, and a
 * schedule that only works while the app is open is not a schedule. A running job holds its own
 * wakelock, so the alternative is sound as long as the work fits in the window the framework
 * gives it.
 *
 * <p>It does not always fit. A first backup of several hundred megabytes over a handheld's
 * connection can exceed the job runtime, and the framework will stop it. That is handled rather
 * than hidden: {@link #onStopJob} asks for a retry, the attempt is recorded as a failure with the
 * reason, and the next run resumes from what the last one managed to write, because an
 * incremental only re-sends what changed. The Settings screen says to run the first backup by
 * hand for this reason.
 */
public class BackupJobService extends JobService {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile boolean stopped;

    @Override public boolean onStartJob(JobParameters params) {
        stopped = false;
        io.execute(() -> {
            boolean retry = run();
            if (!stopped) jobFinished(params, retry);
        });
        // True: the work continues on another thread and jobFinished is called when it ends.
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        stopped = true;
        // Recorded before returning, because a job the framework stopped is exactly the silent
        // failure this app exists to prevent. The person sees it in the log either way.
        record(false, null, 0, "Stopped by the system before it finished. It will try again.");
        return true;
    }

    /** @return true when the job should be retried with backoff. */
    private boolean run() {
        long startedAt = System.currentTimeMillis();
        try {
            ScanSession session = ScanSession.run(this);
            if (!session.ok()) throw new IllegalStateException(session.registryError);
            if (!session.caps.allFiles) {
                // Not retryable. The permission was revoked, and retrying on a backoff will not
                // grant it back; the person has to. Saying so is the only useful thing to do.
                record(false, null, 0, "All-files access is no longer granted, so nothing could "
                        + "be read. Open EmuBackup and grant it again.");
                return false;
            }

            Settings settings = Prefs.settings(this);
            PathResolver resolver =
                    new PathResolver(Environment.getExternalStorageDirectory().getAbsolutePath());
            AppInfo apps = new AppInfo(this);
            FileSource shared = new LocalFileSource();
            FileSource appPrivate = session.caps.appPrivate
                    ? new RemoteFileSource(ShizukuGate.service()) : null;
            ScanEngine scanner = new ScanEngine(resolver, shared, appPrivate, session.caps, apps);
            BackupSink sink = Stores.active(this);

            BackupRunner.Result r = new BackupRunner(session.registry, scanner, resolver, shared,
                    appPrivate, sink, session.caps, apps, apps)
                    .withDevice(appVersion(), android.os.Build.MODEL,
                            android.os.Build.VERSION.SDK_INT)
                    .withRetention(settings.retention())
                    .run(settings.selectedTargets(session.registry), "scheduled", startedAt,
                            new BackupRunner.Listener() {
                        @Override public void onProgress(Progress p) { }
                        @Override public boolean isCancelled() { return stopped; }
                    });

            if (r.cancelled) return true;

            StringBuilder detail = new StringBuilder();
            if (!session.caps.appPrivate) {
                // A scheduled run that quietly drops app-private saves is the staleness trap in
                // ARCHITECTURE.md section 3. Shizuku does not survive a reboot, so this is the
                // normal case, not an exotic one.
                detail.append("Shizuku was not running, so app-private saves were skipped.");
            }
            for (String p : r.problems) {
                if (detail.length() > 0) detail.append('\n');
                detail.append(p);
            }
            record(true, r.versionId, r.archivedBytes, detail.toString());
            return false;

        } catch (Exception e) {
            String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            record(false, null, 0, why);
            // Retried, because most of what lands here is transient: no network, a busy Drive, a
            // file that moved mid-scan. Escalation is what stops a pointless retry loop going on
            // out of sight forever.
            return true;
        }
    }

    private void record(boolean ok, String versionId, long bytes, String detail) {
        RunLog log = Prefs.record(this,
                new RunLog.Run(System.currentTimeMillis(), "scheduled", ok, versionId, bytes, detail));

        if (ok) {
            if (detail != null && !detail.isEmpty()) {
                Notifications.result(this, "Backup finished with problems",
                        Sizes.human(bytes) + " written\n" + detail);
            }
            return;
        }

        if (log.shouldEscalate()) {
            // Three in a row means it is not transient, and a job retrying quietly forever is
            // worse than no backup at all, because the person believes they are covered.
            Prefs.save(this, Prefs.settings(this).withFrequency(Settings.Frequency.OFF));
            Notifications.result(this, "Scheduled backups are paused",
                    log.consecutiveFailures() + " failed in a row, so the schedule is off until "
                            + "you look at it.\n\nLast reason: " + detail);
        } else {
            Notifications.result(this, "Scheduled backup failed", detail);
        }
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "dev";
        }
    }

    @Override public void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }
}
