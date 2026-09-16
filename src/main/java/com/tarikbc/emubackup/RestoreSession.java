package com.tarikbc.emubackup;

import android.content.Context;
import android.os.Environment;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads a backup version and works out what restoring it would do. Blocking; callers run it on a
 * background executor.
 *
 * <p>Produces the plan the preview screen shows. Nothing here writes anything.
 */
public final class RestoreSession {

    public final Manifest manifest;
    public final List<RestorePlan> plans;
    public final String error;
    public final Capabilities caps;

    private RestoreSession(Manifest manifest, List<RestorePlan> plans, Capabilities caps, String error) {
        this.manifest = manifest;
        this.plans = plans;
        this.caps = caps;
        this.error = error;
    }

    public static File storeDir() {
        return new File(Environment.getExternalStorageDirectory(), "EmuBackup");
    }

    public static LocalFolderSink sink() throws java.io.IOException {
        return new LocalFolderSink(storeDir().getAbsolutePath());
    }

    /** The versions present, newest last. */
    public static List<IndexEntry> versions(Context ctx) {
        try {
            LocalFolderSink s = sink();
            if (s.hasRootFile(BackupIndex.FILE_NAME)) {
                return BackupIndex.fromJson(new String(s.readRootFile(BackupIndex.FILE_NAME),
                        java.nio.charset.StandardCharsets.UTF_8)).versions();
            }
            List<Manifest> all = new ArrayList<>();
            for (String v : s.listVersions()) {
                if (!s.hasFile(v, "manifest.json")) continue;
                try (InputStream in = s.openFile(v, "manifest.json")) {
                    all.add(Manifest.fromJson(BackupRunner.readAll(in)));
                } catch (Exception ignored) {
                }
            }
            return BackupIndex.rebuildFrom(all).versions();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static RestoreSession load(Context ctx, String versionId) {
        Capabilities caps = new Capabilities(Permissions.hasAllFiles(), false,
                OAuthConfig.isConfigured());
        try {
            LocalFolderSink s = sink();
            Manifest m;
            try (InputStream in = s.openFile(versionId, "manifest.json")) {
                m = Manifest.fromJson(BackupRunner.readAll(in));
            }

            TargetRegistry reg = TargetRegistry.parse(Assets.readString(ctx, "targets.json"));
            LocalFileSource src = new LocalFileSource();
            List<RestorePlan> plans = new ArrayList<>();

            for (ManifestTarget mt : m.targets) {
                if (mt.files.isEmpty()) continue;
                String label = reg.hasTarget(mt.id) ? reg.target(mt.id).label : mt.id;
                boolean writable = caps.canRead(mt.tier);

                List<FileStat> onDevice = new ArrayList<>();
                if (writable && src.exists(mt.root)) {
                    try {
                        onDevice = src.walk(mt.root, true);
                    } catch (Exception ignored) {
                        // Unreadable root. Treated as empty, which makes every file a CREATE and
                        // the preview will show that plainly rather than pretending to know more.
                    }
                }
                final String root = mt.root;
                plans.add(RestorePlanner.plan(mt, label, onDevice, null, rel -> {
                    try (InputStream in = src.open(root, rel)) {
                        return Hashes.sha256(in);
                    }
                }, writable));
            }
            return new RestoreSession(m, plans, caps, null);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new RestoreSession(null, new ArrayList<>(), caps, msg);
        }
    }

    public boolean ok() {
        return error == null;
    }

    public int totalToWrite() {
        int n = 0;
        for (RestorePlan p : plans) n += p.toWrite().size();
        return n;
    }

    public long bytesToWrite() {
        long n = 0;
        for (RestorePlan p : plans) n += p.bytesToWrite();
        return n;
    }

    public int totalConflicts() {
        int n = 0;
        for (RestorePlan p : plans) n += p.count(RestoreAction.CONFLICT_NEWER);
        return n;
    }

    public int totalBlocked() {
        int n = 0;
        for (RestorePlan p : plans) n += p.count(RestoreAction.BLOCKED_TIER);
        return n;
    }
}
