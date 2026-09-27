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
        /** True when {@link #list} is the last index this device read, because the store is unreachable now. */
        public final boolean stale;

        Versions(List<IndexEntry> list, String error) {
            this(list, error, false);
        }

        Versions(List<IndexEntry> list, String error, boolean stale) {
            this.list = list;
            this.error = error;
            this.stale = stale;
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
        long t0 = System.currentTimeMillis();
        try {
            BackupSink s = Stores.active(ctx);
            // Read the index outright rather than asking whether it exists first: on Drive each
            // question is a listing of the root folder, and one of those is enough.
            byte[] index = null;
            try {
                index = s.readRootFile(BackupIndex.FILE_NAME);
            } catch (java.io.FileNotFoundException absent) {
                // No index yet: an old store, or an empty one. Rebuilt from the manifests below.
            }
            if (index != null) {
                String json = new String(index, java.nio.charset.StandardCharsets.UTF_8);
                List<IndexEntry> list = BackupIndex.fromJson(json).versions();
                rememberIndex(ctx, json);
                android.util.Log.d("EmuBackup", "store: index read after " + (System.currentTimeMillis() - t0) + " ms");
                return new Versions(list, null);
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
            android.util.Log.d("EmuBackup", "store: unreachable after " + (System.currentTimeMillis() - t0) + " ms: " + why);
            // The manifests are already local (ManifestCache), so the last index this device read
            // is enough to keep every game's history on screen while the store is out of reach.
            List<IndexEntry> remembered = rememberedIndex(ctx);
            return new Versions(remembered, why, !remembered.isEmpty());
        }
    }

    private static java.io.File indexCache(Context ctx) {
        java.io.File dir = new java.io.File(new java.io.File(ctx.getFilesDir(), "manifests"), Stores.key(ctx));
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new java.io.File(dir, BackupIndex.FILE_NAME);
    }

    private static void rememberIndex(Context ctx, String json) {
        try {
            java.io.File f = indexCache(ctx);
            java.io.File tmp = new java.io.File(f.getPath() + ".part");
            java.nio.file.Files.write(tmp.toPath(), json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (!tmp.renameTo(f)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Exception ignored) {
            // A cache miss later costs a blank history while offline, not correctness.
        }
    }

    private static List<IndexEntry> rememberedIndex(Context ctx) {
        try {
            java.io.File f = indexCache(ctx);
            if (!f.isFile()) return new ArrayList<>();
            return BackupIndex.fromJson(new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8)).versions();
        } catch (Exception corrupt) {
            return new ArrayList<>();
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
        return load(ctx, versionId, filter, HomeModel.QUIET);
    }

    /**
     * @param note where the comparison is, in words: reading the backup's file list (from the
     *             local cache when it has been read before, else from the store), then checking
     *             the device's files
     */
    public static RestoreSession load(Context ctx, String versionId,
                                      java.util.Map<String, java.util.Set<String>> filter,
                                      HomeModel.Note note) {
        ShizukuGate.Status shizuku = ShizukuGate.connect(ctx);
        Capabilities caps = new Capabilities(Permissions.hasAllFiles(), shizuku.ready(),
                DriveClient.of(ctx).configured());
        try {
            ManifestCache cache = new ManifestCache(ctx);
            note.say(cache.has(versionId) ? "Reading the backup's file list\u2026"
                    : "Reading the backup's file list from " + describeStore(ctx) + "\u2026");
            BackupSink s = Stores.active(ctx);
            Manifest m = cache.get(s, versionId);

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
                        note.say("Checking " + label + " on this device\u2026");
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
