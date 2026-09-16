package com.tarikbc.emubackup;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs a backup in the foreground so it survives the screen turning off.
 *
 * <p>A full run moves hundreds of megabytes and takes minutes. No {@code WorkManager} is used —
 * see {@code ARCHITECTURE.md} — so the framework answer is a foreground service holding a partial
 * wakelock, started from a visible Activity so the background-start restrictions never apply.
 *
 * <p>The Activity link is two static fields rather than a binder or a LiveData: the Activity reads
 * the latest snapshot on resume and receives pushes while it is on screen, and the run continues
 * untouched when there is no Activity at all.
 */
public class BackupService extends Service {

    /** How many problem lines the finished screen names before summarising the rest. */
    private static final int PROBLEMS_SHOWN = 6;

    public interface Listener {
        void onProgress(Progress p);
        void onFinished(String summary, boolean failed);
    }

    /** A restore the preview screen has approved. Set immediately before starting the service. */
    public static final class RestoreRequest {
        public final String versionId;
        public final java.util.List<RestorePlan> plans;

        public RestoreRequest(String versionId, java.util.List<RestorePlan> plans) {
            this.versionId = versionId;
            this.plans = plans;
        }
    }

    public static volatile RestoreRequest PENDING_RESTORE;

    /** The most recent snapshot, readable by whichever Activity comes to the front. */
    public static volatile Progress PROGRESS;
    public static volatile Listener LISTENER;
    public static volatile boolean RUNNING;

    private static volatile boolean cancelRequested;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private PowerManager.WakeLock wakeLock;
    private volatile boolean restoring;

    public static void start(Context ctx) {
        PENDING_RESTORE = null;
        ctx.startForegroundService(new Intent(ctx, BackupService.class));
    }

    public static void startRestore(Context ctx, RestoreRequest request) {
        PENDING_RESTORE = request;
        ctx.startForegroundService(new Intent(ctx, BackupService.class));
    }

    public static void requestCancel() {
        cancelRequested = true;
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (RUNNING) return START_NOT_STICKY;
        RUNNING = true;
        cancelRequested = false;

        restoring = PENDING_RESTORE != null;
        Notifications.ensureChannels(this);
        startForeground(Notifications.ID_PROGRESS,
                Notifications.progress(this, restoring ? "Restoring" : "Backing up", "Starting…", -1),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);

        PowerManager pm = getSystemService(PowerManager.class);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EmuBackup:backup");
        wakeLock.acquire(30 * 60 * 1000L);

        io.execute(restoring ? this::runRestore : this::runBackup);
        return START_NOT_STICKY;
    }

    private void runBackup() {
        String summary;
        boolean failed = false;
        try {
            ScanSession session = ScanSession.run(this);
            if (!session.ok()) throw new IllegalStateException(session.registryError);
            if (!session.caps.allFiles) throw new IllegalStateException("all-files access not granted");

            BackupSink sink = chooseSink();

            PathResolver resolver =
                    new PathResolver(Environment.getExternalStorageDirectory().getAbsolutePath());
            AppInfo apps = new AppInfo(this);
            FileSource shared = new LocalFileSource();
            FileSource appPrivate = session.caps.appPrivate
                    ? new RemoteFileSource(ShizukuGate.service()) : null;
            ScanEngine scanner = new ScanEngine(resolver, shared, appPrivate, session.caps, apps);

            BackupRunner runner = new BackupRunner(session.registry, scanner, resolver, shared,
                    appPrivate, sink, session.caps, apps, apps)
                    .withDevice(appVersion(), android.os.Build.MODEL, android.os.Build.VERSION.SDK_INT);

            BackupRunner.Result r = runner.run(null, "manual", System.currentTimeMillis(),
                    new BackupRunner.Listener() {
                        @Override public void onProgress(Progress p) { publish(p); }
                        @Override public boolean isCancelled() { return cancelRequested; }
                    });

            if (r.cancelled) {
                summary = "Cancelled. Nothing was recorded.";
                publish(Progress.of(Progress.Phase.CANCELLED, summary));
            } else {
                summary = r.versionId + " · " + Sizes.human(r.archivedBytes) + " written to "
                        + sink.describe();
                // A bare count is useless: it tells you something is wrong and nothing about
                // what, and the list is gone once this screen closes. Name the targets, but
                // cap the list so a bad run cannot bury the version id under thirty lines.
                int shown = Math.min(r.problems.size(), PROBLEMS_SHOWN);
                for (int i = 0; i < shown; i++) summary += "\n" + r.problems.get(i);
                int rest = r.problems.size() - shown;
                if (rest > 0) summary += "\nand " + rest + " more";
                publish(Progress.of(Progress.Phase.DONE, summary));
            }
        } catch (Exception e) {
            failed = true;
            summary = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            publish(Progress.of(Progress.Phase.FAILED, summary));
        }

        Notifications.result(this, failed ? "Backup failed" : "Backup finished", summary);
        Listener l = LISTENER;
        if (l != null) l.onFinished(summary, failed);

        RUNNING = false;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void runRestore() {
        RestoreRequest request = PENDING_RESTORE;
        String summary;
        boolean failed = false;
        try {
            if (request == null) throw new IllegalStateException("no restore was requested");
            BackupSink sink = Stores.active(this);
            Manifest m;
            try (java.io.InputStream in = sink.openFile(request.versionId, "manifest.json")) {
                m = Manifest.fromJson(BackupRunner.readAll(in));
            }
            ShizukuGate.Status shizuku = ShizukuGate.connect(this);
            Capabilities caps = new Capabilities(Permissions.hasAllFiles(), shizuku.ready(),
                    OAuthConfig.isConfigured());
            FileSource appPrivate = shizuku.ready()
                    ? new RemoteFileSource(ShizukuGate.service()) : null;
            FileSink appPrivateSink = shizuku.ready()
                    ? new RemoteFileSink(ShizukuGate.service()) : null;

            RestoreRunner runner = new RestoreRunner(sink, new LocalFileSource(), appPrivate,
                    new LocalFileSink(), appPrivateSink, caps);
            RestoreRunner.Result r = runner.run(m, request.plans, System.currentTimeMillis(),
                    new RestoreRunner.Listener() {
                        @Override public void onProgress(Progress p) { publish(p); }
                        @Override public boolean isCancelled() { return cancelRequested; }
                    });

            StringBuilder b = new StringBuilder();
            b.append(r.cancelled ? "Cancelled after " : "Restored ")
                    .append(r.filesWritten).append(" files (").append(Sizes.human(r.bytesWritten)).append(")");
            if (r.snapshotVersionId != null) {
                b.append("\nWhat was replaced is saved in ").append(r.snapshotVersionId);
            }
            if (!r.corrupt.isEmpty()) b.append("\n").append(r.corrupt.size())
                    .append(" file(s) failed verification and were left alone");
            if (!r.missing.isEmpty()) b.append("\n").append(r.missing.size())
                    .append(" file(s) could not be found in the archives");
            if (!r.failures.isEmpty()) b.append("\n").append(String.join("\n", r.failures));
            failed = !r.ok() && !r.cancelled;
            summary = b.toString();
            publish(Progress.of(r.cancelled ? Progress.Phase.CANCELLED : Progress.Phase.DONE, summary));
        } catch (Exception e) {
            failed = true;
            summary = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            publish(Progress.of(Progress.Phase.FAILED, summary));
        }

        PENDING_RESTORE = null;
        Notifications.result(this, failed ? "Restore failed" : "Restore finished", summary);
        Listener l = LISTENER;
        if (l != null) l.onFinished(summary, failed);

        RUNNING = false;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    /**
     * The store this run writes to, resolved by {@link Stores} so that a backup written here can
     * always be read back by the Backups screen and the restore path.
     */
    private BackupSink chooseSink() throws java.io.IOException {
        return Stores.active(this, new DriveApi.ProgressListener() {
            @Override public void onProgress(long sent, long total) {
                publish(new Progress(Progress.Phase.ARCHIVING, null, "Uploading", 0, 0,
                        null, 0, 0, sent, total, null));
            }
            @Override public boolean isCancelled() { return cancelRequested; }
        });
    }

    private void publish(Progress p) {
        PROGRESS = p;
        Listener l = LISTENER;
        if (l != null) l.onProgress(p);

        String text = p.targetLabel != null
                ? p.targetLabel + (p.fileName == null ? "" : " · " + p.fileName)
                : (p.message == null ? "" : p.message);
        getSystemService(android.app.NotificationManager.class).notify(Notifications.ID_PROGRESS,
                Notifications.progress(this, restoring ? "Restoring" : "Backing up", text, p.percent()));
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    @Override public void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
        RUNNING = false;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }
}
