package com.tarikbc.emubackup;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A local copy of every version's {@code manifest.json}.
 *
 * <p>Reading a manifest from Drive is three to five HTTP round trips of about 250 KB, and
 * {@code Stores.active()} builds a fresh sink each call, so nothing is remembered between
 * screens. A Games screen that needs every manifest would do that N times per visit. Manifests
 * are immutable per version id, so caching them forever is correct, and they live under
 * {@code getFilesDir()} rather than the cache dir because the system may purge the latter.
 *
 * <p>Keyed by store, because version ids are per-store counters. Evicted from the index, and
 * only from an index that was actually read: an empty list born of an exception must not wipe
 * the cache. Never probes an id that is not in the index, because {@code DriveSink} creates a
 * folder for an unknown id on lookup.
 */
public final class ManifestCache {

    private final File dir;

    public ManifestCache(Context ctx) {
        this.dir = new File(new File(ctx.getFilesDir(), "manifests"), Stores.key(ctx));
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    private File fileFor(String versionId) {
        return new File(dir, versionId + ".json");
    }

    public boolean has(String versionId) {
        return fileFor(versionId).isFile();
    }

    /** Stores a manifest the app has just produced, so it is never fetched back. */
    public void put(Manifest m) {
        if (m == null) return;
        write(m.version, m.toJson());
    }

    /** The cached manifest, or fetched from the store and cached. */
    public Manifest get(BackupSink store, String versionId) throws IOException {
        File f = fileFor(versionId);
        if (f.isFile()) {
            try {
                return Manifest.fromJson(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            } catch (Exception corrupt) {
                android.util.Log.w("EmuBackup", "manifest cache: " + f.getName() + " unreadable, refetching: " + corrupt);
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        String json;
        try (InputStream in = store.openFile(versionId, "manifest.json")) {
            json = BackupRunner.readAll(in);
        }
        Manifest m = Manifest.fromJson(json);
        // The bytes as the store holds them, not a re-serialisation: what parsed once parses again.
        write(versionId, json);
        return m;
    }

    private void write(String versionId, String json) {
        try {
            File tmp = new File(dir, versionId + ".part");
            Files.write(tmp.toPath(), json.getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(fileFor(versionId))) {
                android.util.Log.w("EmuBackup", "manifest cache: could not place " + fileFor(versionId));
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (IOException e) {
            // A cache miss later costs a download, not correctness.
            android.util.Log.w("EmuBackup", "manifest cache: could not write " + versionId + ": " + e);
        }
    }

    /** Drops cached manifests for versions no longer in the index. */
    public void evictNotIn(List<IndexEntry> index) {
        Set<String> keep = new HashSet<>();
        for (IndexEntry e : index) keep.add(e.id + ".json");
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            // The remembered index lives beside the manifests and is not one of them.
            if (BackupIndex.FILE_NAME.equals(f.getName())) continue;
            if (f.getName().endsWith(".json") && !keep.contains(f.getName())) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }
}
