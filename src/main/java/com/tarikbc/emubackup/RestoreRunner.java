package com.tarikbc.emubackup;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies a set of {@link RestorePlan}s to the device.
 *
 * <p>Two rules shape this class, both of them about not destroying data.
 *
 * <p>First, nothing is written until a snapshot of exactly the files about to be replaced has
 * been captured, as a pinned backup version of its own. A tool whose purpose is preventing data
 * loss must not itself be a way to lose data, so if the snapshot cannot be written the restore
 * does not start.
 *
 * <p>Second, every file is written to a temporary sibling, verified against the manifest's hash,
 * and only then renamed into place. A restore killed halfway leaves the original saves intact and
 * at worst some stray temporary files, never a truncated save where a working one used to be.
 *
 * <p>Android-free by design; see {@code test.sh}.
 */
public final class RestoreRunner {

    public interface Listener {
        void onProgress(Progress p);
        boolean isCancelled();
    }

    public static final Listener SILENT = new Listener() {
        @Override public void onProgress(Progress p) {}
        @Override public boolean isCancelled() { return false; }
    };

    public static final class Result {
        public final String snapshotVersionId;
        /** The safety copy's manifest, so a cache can keep it without a round trip. Null when none. */
        public final Manifest snapshotManifest;
        public final int filesWritten;
        public final long bytesWritten;
        public final boolean cancelled;
        public final List<String> missing;
        public final List<String> corrupt;
        public final List<String> failures;

        Result(String snapshotVersionId, Manifest snapshotManifest, int filesWritten,
               long bytesWritten, boolean cancelled,
               List<String> missing, List<String> corrupt, List<String> failures) {
            this.snapshotVersionId = snapshotVersionId;
            this.snapshotManifest = snapshotManifest;
            this.filesWritten = filesWritten;
            this.bytesWritten = bytesWritten;
            this.cancelled = cancelled;
            this.missing = missing;
            this.corrupt = corrupt;
            this.failures = failures;
        }

        public boolean ok() {
            return !cancelled && missing.isEmpty() && corrupt.isEmpty() && failures.isEmpty();
        }
    }

    private final BackupSink sink;
    private final FileSource shared;
    private final FileSource appPrivate;
    private final FileSink sharedSink;
    private final FileSink appPrivateSink;
    private final Capabilities caps;

    public RestoreRunner(BackupSink sink, FileSource shared, FileSource appPrivate,
                         FileSink sharedSink, FileSink appPrivateSink, Capabilities caps) {
        this.sink = sink;
        this.shared = shared;
        this.appPrivate = appPrivate;
        this.sharedSink = sharedSink;
        this.appPrivateSink = appPrivateSink;
        this.caps = caps;
    }

    /**
     * @param manifest the version being restored from
     * @param plans    what to do, already reviewed by the user
     */
    public Result run(Manifest manifest, List<RestorePlan> plans, long nowMs, Listener listener)
            throws IOException {

        List<String> failures = new ArrayList<>();
        Manifest snapshotManifest = snapshot(manifest, plans, nowMs, listener);
        String snapshotId = snapshotManifest == null ? null : snapshotManifest.version;

        int written = 0;
        long bytes = 0;
        List<String> missing = new ArrayList<>(), corrupt = new ArrayList<>();
        boolean cancelled = false;

        int idx = 0;
        for (RestorePlan plan : plans) {
            if (listener.isCancelled()) { cancelled = true; break; }
            idx++;

            List<RestoreItem> write = plan.toWrite();
            if (write.isEmpty()) continue;

            ManifestTarget mt = manifest.target(plan.targetId);
            if (mt == null) {
                failures.add(plan.targetId + ": not present in this backup version");
                continue;
            }
            final FileSink dest = plan.tier == Tier.SHARED ? sharedSink : appPrivateSink;
            if (dest == null || !dest.available()) {
                failures.add(plan.targetLabel + ": cannot write to this storage tier");
                continue;
            }

            Map<String, ManifestFile> wanted = new LinkedHashMap<>();
            for (RestoreItem i : write) wanted.put(i.path, i.backup);

            final String root = plan.root;
            final int fIdx = idx, fCount = plans.size();
            final String label = plan.targetLabel;

            ArchiveReader.Result r = ArchiveReader.extract(sink, mt.chain, wanted,
                    new ArchiveReader.Writer() {
                        @Override public OutputStream open(String rel) throws IOException {
                            return dest.createTemp(root, rel);
                        }
                        @Override public void commit(String rel, ManifestFile expected) throws IOException {
                            dest.commit(root, rel);
                            try {
                                dest.setMtime(root, rel, expected.mtimeMs);
                            } catch (IOException ignored) {
                                // Not fatal. The manifest is authoritative for timestamps
                                // (FORMAT.md section 5), and some filesystems refuse the call.
                            }
                        }
                        @Override public void discard(String rel) {
                            dest.discardTemp(root, rel);
                        }
                    },
                    new ArchiveReader.Listener() {
                        @Override public void onFile(String p, int i, int n, long bd, long bt) {
                            listener.onProgress(new Progress(Progress.Phase.ARCHIVING, plan.targetId,
                                    label, fIdx, fCount, p, i, n, bd, bt, null));
                        }
                        @Override public boolean isCancelled() { return listener.isCancelled(); }
                    });

            written += r.written;
            bytes += r.bytes;
            missing.addAll(r.missing);
            corrupt.addAll(r.corrupt);
            if (r.cancelled) { cancelled = true; break; }
        }

        listener.onProgress(Progress.of(cancelled ? Progress.Phase.CANCELLED : Progress.Phase.DONE,
                written + " files restored"));
        return new Result(snapshotId, snapshotManifest, written, bytes, cancelled, missing, corrupt, failures);
    }

    /**
     * Captures exactly the files about to be replaced, as a pinned version of its own.
     *
     * @return the snapshot's version id, or null when nothing would be overwritten
     * @throws IOException if the snapshot cannot be written, which aborts the restore
     */
    private Manifest snapshot(Manifest manifest, List<RestorePlan> plans, long nowMs, Listener listener)
            throws IOException {

        Map<String, List<RestoreItem>> perTarget = new LinkedHashMap<>();
        for (RestorePlan p : plans) {
            List<RestoreItem> over = p.toOverwrite();
            if (!over.isEmpty()) perTarget.put(p.targetId, over);
        }
        if (perTarget.isEmpty()) return null;

        listener.onProgress(Progress.of(Progress.Phase.ARCHIVING, "Saving what is about to change"));

        BackupIndex index = loadIndex();
        String vid = VersionId.next(index.highestCounter(), nowMs).id() + "-prerestore";
        sink.ensureVersion(vid);

        List<ManifestTarget> targets = new ArrayList<>();
        for (RestorePlan p : plans) {
            List<RestoreItem> over = perTarget.get(p.targetId);
            if (over == null) continue;

            final FileSource src = p.tier == Tier.SHARED ? shared : appPrivate;
            final String root = p.root;

            List<FileStat> stats = new ArrayList<>();
            for (RestoreItem i : over) stats.add(i.device);

            Plan plan = DiffEngine.diff(p.targetId, stats, null, rel -> {
                try (InputStream in = src.open(root, rel)) {
                    return Hashes.sha256(in);
                }
            }, vid);

            String archive = p.targetId + ".full.zip";
            ArchiveWriter.Result w;
            try (OutputStream out = sink.createArchive(vid, archive)) {
                w = ArchiveWriter.write(out, root, src, plan, false, ArchiveWriter.SILENT);
            }
            sink.commitArchive(vid, archive);

            List<String> chain = new ArrayList<>();
            chain.add(vid + "/" + archive);
            targets.add(new ManifestTarget(p.targetId, null, p.tier, Category.SAVE, root,
                    TargetStatus.OK, "full", null, archive, w.sha256, w.archiveBytes,
                    w.archiveBytes, 0, chain, null, -1, plan.files, new ArrayList<>(),
                    new ArrayList<>(), "captured before restoring " + manifest.version));
        }

        Manifest snap = new Manifest(vid, nowMs, manifest.appVersionName, manifest.registryVersion,
                null, manifest.deviceModel, manifest.androidSdk, manifest.extRoot, caps, targets);

        sink.writeFile(vid, "manifest.json", snap.toJson().getBytes(StandardCharsets.UTF_8));
        sink.writeFile(vid, "SHA256SUMS", RestoreScript.sha256sums(snap).getBytes(StandardCharsets.UTF_8));
        sink.writeFile(vid, "RESTORE.txt", RestoreScript.versionReadme(snap).getBytes(StandardCharsets.UTF_8));

        BackupIndex updated = index.with(new IndexEntry(vid, nowMs, snap.totalBytes(), true, "prerestore"));
        sink.writeRootFile(BackupIndex.FILE_NAME, updated.toJson().getBytes(StandardCharsets.UTF_8));
        sink.writeRootFile("RESTORE.txt",
                RestoreScript.storeReadme(updated.versions()).getBytes(StandardCharsets.UTF_8));
        return snap;
    }

    private BackupIndex loadIndex() {
        try {
            if (sink.hasRootFile(BackupIndex.FILE_NAME)) {
                return BackupIndex.fromJson(new String(
                        sink.readRootFile(BackupIndex.FILE_NAME), StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
        return BackupIndex.empty();
    }
}
