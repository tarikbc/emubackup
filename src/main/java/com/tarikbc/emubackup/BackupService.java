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

    public interface Listener {
        void onProgress(Progress p);
        void onFinished(String summary, boolean failed);
    }

    /** The most recent snapshot, readable by whichever Activity comes to the front. */
    public static volatile Progress PROGRESS;
    public static volatile Listener LISTENER;
    public static volatile boolean RUNNING;

    private static volatile boolean cancelRequested;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private PowerManager.WakeLock wakeLock;

    public static void start(Context ctx) {
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

        Notifications.ensureChannels(this);
        startForeground(Notifications.ID_PROGRESS,
                Notifications.progress(this, "Backing up", "Scanning…", -1),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);

        PowerManager pm = getSystemService(PowerManager.class);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EmuBackup:backup");
        wakeLock.acquire(30 * 60 * 1000L);

        io.execute(this::runBackup);
        return START_NOT_STICKY;
    }

    private void runBackup() {
        String summary;
        boolean failed = false;
        try {
            ScanSession session = ScanSession.run(this);
            if (!session.ok()) throw new IllegalStateException(session.registryError);
            if (!session.caps.allFiles) throw new IllegalStateException("all-files access not granted");

            File dir = new File(Environment.getExternalStorageDirectory(), "EmuBackup");
            LocalFolderSink sink = new LocalFolderSink(dir.getAbsolutePath());

            PathResolver resolver =
                    new PathResolver(Environment.getExternalStorageDirectory().getAbsolutePath());
            AppInfo apps = new AppInfo(this);
            FileSource shared = new LocalFileSource();
            ScanEngine scanner = new ScanEngine(resolver, shared, null, session.caps, apps);

            BackupRunner runner = new BackupRunner(session.registry, scanner, resolver, shared, null,
                    sink, session.caps, apps, apps)
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
                        + sink.rootPath();
                if (!r.problems.isEmpty()) summary += "\n" + r.problems.size() + " problem(s)";
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

    private void publish(Progress p) {
        PROGRESS = p;
        Listener l = LISTENER;
        if (l != null) l.onProgress(p);

        String text = p.targetLabel != null
                ? p.targetLabel + (p.fileName == null ? "" : " · " + p.fileName)
                : (p.message == null ? "" : p.message);
        getSystemService(android.app.NotificationManager.class).notify(Notifications.ID_PROGRESS,
                Notifications.progress(this, "Backing up", text, p.percent()));
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
