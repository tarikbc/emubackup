package com.tarikbc.emubackup;

import android.content.Context;
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

    /** Where the active store keeps its versions, for display. */
    public static String describeStore(Context ctx) {
        try {
            return Stores.active(ctx).describe();
        } catch (Exception e) {
            return Stores.localRoot().getAbsolutePath();
        }
    }

    /** The versions present, newest last. */
    public static List<IndexEntry> versions(Context ctx) {
        try {
            BackupSink s = Stores.active(ctx);
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
        ShizukuGate.Status shizuku = ShizukuGate.connect(ctx);
        Capabilities caps = new Capabilities(Permissions.hasAllFiles(), shizuku.ready(),
                OAuthConfig.isConfigured());
        try {
            BackupSink s = Stores.active(ctx);
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

                // App-private roots are only reachable through the privileged service, so the
                // preview must read them the same way the restore will write them.
                final FileSource reader = mt.tier == Tier.SHARED ? src
                        : (shizuku.ready() ? new RemoteFileSource(ShizukuGate.service()) : null);

                List<FileStat> onDevice = new ArrayList<>();
                if (writable && reader != null && reader.exists(mt.root)) {
                    try {
                        onDevice = reader.walk(mt.root, true);
                    } catch (Exception ignored) {
                        // Unreadable root. Treated as empty, which makes every file a CREATE and
                        // the preview will show that plainly rather than pretending to know more.
                    }
                }
                final String root = mt.root;
                plans.add(RestorePlanner.plan(mt, label, onDevice, null, rel -> {
                    try (InputStream in = reader.open(root, rel)) {
                        return Hashes.sha256(in);
                    }
                }, writable && reader != null));
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
