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

    /** The versions in a store, or the reason they could not be listed. */
    public static final class Versions {
        /** Newest last. Empty when the store is empty or unreachable; check {@link #error}. */
        public final List<IndexEntry> list;
        /** Null when the store was read. Otherwise why it could not be, in plain words. */
        public final String error;

        Versions(List<IndexEntry> list, String error) {
            this.list = list;
            this.error = error;
        }

        public boolean reachable() {
            return error == null;
        }
    }

    /** The versions present, newest last; the old shape, kept for callers that cannot show an error. */
    public static List<IndexEntry> versions(Context ctx) {
        return listVersions(ctx).list;
    }

    /**
     * Lists versions and says when it could not.
     *
     * <p>The previous version of this swallowed every exception into an empty list, so a Drive
     * outage rendered as "No backups yet here". Empty and unreachable are different facts and a
     * person deciding whether to trust their backups needs to know which one they are looking at.
     */
    public static Versions listVersions(Context ctx) {
        try {
            BackupSink s = Stores.active(ctx);
            if (s.hasRootFile(BackupIndex.FILE_NAME)) {
                return new Versions(BackupIndex.fromJson(new String(s.readRootFile(BackupIndex.FILE_NAME),
                        java.nio.charset.StandardCharsets.UTF_8)).versions(), null);
            }
            List<Manifest> all = new ArrayList<>();
            for (String v : s.listVersions()) {
                if (!s.hasFile(v, "manifest.json")) continue;
                try (InputStream in = s.openFile(v, "manifest.json")) {
                    all.add(Manifest.fromJson(BackupRunner.readAll(in)));
                } catch (Exception ignored) {
                }
            }
            return new Versions(BackupIndex.rebuildFrom(all).versions(), null);
        } catch (Exception e) {
            String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new Versions(new ArrayList<>(), why);
        }
    }

    public static RestoreSession load(Context ctx, String versionId) {
        return load(ctx, versionId, null);
    }

    /**
     * @param filter target id to the relative paths to consider, or null for everything. A
     *               target absent from the map is skipped entirely. This is what a per-game
     *               restore is: {@code RestorePlanner} already takes a selection, and the
     *               pre-restore snapshot already scopes itself to what will be overwritten.
     */
    public static RestoreSession load(Context ctx, String versionId,
                                      java.util.Map<String, java.util.Set<String>> filter) {
        ShizukuGate.Status shizuku = ShizukuGate.connect(ctx);
        Capabilities caps = new Capabilities(Permissions.hasAllFiles(), shizuku.ready(),
                DriveClient.of(ctx).configured());
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
                if (filter != null && !filter.containsKey(mt.id)) continue;
                final java.util.Set<String> selected = filter == null ? null : filter.get(mt.id);
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
                plans.add(RestorePlanner.plan(mt, label, onDevice, selected, rel -> {
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
