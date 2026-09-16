package com.tarikbc.emubackup;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Works out what actually changed since the last backup: stat everything, hash almost nothing.
 *
 * <p>The ordering matters for both speed and size.
 *
 * <ol>
 *   <li>A file absent from the previous manifest is new and must be hashed and archived.
 *   <li>Same size <em>and</em> same modification time is treated as unchanged with no read at
 *       all. This is the fast path that skips hundreds of megabytes of untouched save states.
 *   <li>Anything else is hashed — and if the content turns out identical, it is <em>not</em>
 *       archived. That case is not an optimisation but the whole point: emulators rewrite save
 *       files wholesale on exit, so modification times churn constantly while the bytes do not.
 *       Without the content check every run would re-upload the entire Switch save tree.
 * </ol>
 *
 * <p>Android-free by design; see {@code test.sh}.
 */
public final class DiffEngine {

    /** Reads one target-relative file and returns its lowercase-hex SHA-256. */
    public interface Hasher {
        String sha256(String relPath) throws IOException;
    }

    /**
     * @param prior      the same target's entry in the previous manifest, or null for a first run
     * @param newVersion the id of the version being written, stamped on anything archived now
     */
    public static Plan diff(String targetId, List<FileStat> current, List<ManifestFile> prior,
                            Hasher hasher, String newVersion) throws IOException {
        Map<String, ManifestFile> before = new LinkedHashMap<>();
        if (prior != null) for (ManifestFile f : prior) before.put(f.path, f);

        List<FileStat> toArchive = new ArrayList<>();
        List<ManifestFile> files = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        int added = 0, changed = 0, unchanged = 0, rehashed = 0;

        for (FileStat now : current) {
            ManifestFile was = before.remove(now.path);

            if (was == null) {
                String hash = hashOrNull(hasher, now.path, unreadable);
                if (hash == null) continue;
                files.add(new ManifestFile(now.path, now.size, now.mtimeMs, hash, newVersion));
                toArchive.add(now);
                added++;
                continue;
            }

            if (was.size == now.size && was.mtimeMs == now.mtimeMs) {
                files.add(was);
                unchanged++;
                continue;
            }

            String hash = hashOrNull(hasher, now.path, unreadable);
            if (hash == null) {
                // Unreadable now but readable before. The previous entry is still valid, so it is
                // carried forward: the bytes remain recoverable from the older archive.
                files.add(was);
                unchanged++;
                continue;
            }
            if (hash.equals(was.sha256)) {
                // Same bytes, new timestamp. Carry the old entry forward so the bytes are not
                // rewritten, but record the current mtime so the next run takes the fast path
                // above instead of hashing this file again forever.
                files.add(was.carriedForward(now.mtimeMs));
                rehashed++;
                unchanged++;
            } else {
                files.add(new ManifestFile(now.path, now.size, now.mtimeMs, hash, newVersion));
                toArchive.add(now);
                changed++;
            }
        }

        // Whatever is left in `before` was in the previous manifest and is gone now.
        List<String> deleted = new ArrayList<>(before.keySet());

        return new Plan(targetId, toArchive, files, deleted, unreadable,
                added, changed, unchanged, rehashed);
    }

    /**
     * Hashes a file, or records it as unreadable and returns null.
     *
     * <p>A file the caller can stat but not open is a permissions fact about the device, not a
     * failure of the backup. Aborting here would discard every other save in the run.
     */
    private static String hashOrNull(Hasher hasher, String path, List<String> unreadable) {
        try {
            return hasher.sha256(path);
        } catch (IOException e) {
            unreadable.add(path);
            return null;
        }
    }

    /**
     * Rewrites an incremental plan as a full one.
     *
     * <p>No file is re-read. Entries carried forward from an earlier version already hold a hash
     * of content that has not changed, so promoting them only means restamping which archive the
     * bytes now live in.
     */
    public static Plan asFull(Plan plan, List<FileStat> current, String newVersion) {
        List<ManifestFile> files = new ArrayList<>(plan.files.size());
        for (ManifestFile f : plan.files) {
            files.add(new ManifestFile(f.path, f.size, f.mtimeMs, f.sha256, newVersion));
        }
        // Only files that were readable have manifest entries, so a full archive covers those
        // and no more; an unreadable file cannot be promoted into one.
        List<FileStat> archive = new ArrayList<>();
        java.util.Set<String> known = new java.util.HashSet<>();
        for (ManifestFile f : files) known.add(f.path);
        for (FileStat f : current) if (known.contains(f.path)) archive.add(f);

        return new Plan(plan.targetId, archive, files, plan.deleted, plan.unreadable,
                archive.size(), 0, 0, plan.rehashedCount);
    }

    private DiffEngine() {}
}
