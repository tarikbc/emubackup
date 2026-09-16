package com.tarikbc.emubackup;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Runs one backup: scan, diff, archive, and write the manifest.
 *
 * <p>Android-free on purpose. Everything that can lose or mis-record a save happens in this
 * class, so it is exercised end to end on a JVM against temp directories, with no emulator, no
 * device and no network. See {@code test.sh}.
 *
 * <p>Nothing is committed until the manifest and index are written, so a cancelled or crashed
 * run leaves an orphan version directory that the next run replaces, never a half-recorded
 * backup that looks complete.
 */
public final class BackupRunner {

    public interface Listener {
        void onProgress(Progress p);
        boolean isCancelled();
    }

    public static final Listener SILENT = new Listener() {
        @Override public void onProgress(Progress p) {}
        @Override public boolean isCancelled() { return false; }
    };

    public static final class Result {
        public final String versionId;
        public final Manifest manifest;
        public final boolean cancelled;
        public final int archivedFiles;
        public final long archivedBytes;
        public final List<String> problems;

        Result(String versionId, Manifest manifest, boolean cancelled, int archivedFiles,
               long archivedBytes, List<String> problems) {
            this.versionId = versionId;
            this.manifest = manifest;
            this.cancelled = cancelled;
            this.archivedFiles = archivedFiles;
            this.archivedBytes = archivedBytes;
            this.problems = problems;
        }
    }

    private final TargetRegistry registry;
    private final ScanEngine scanner;
    private final PathResolver resolver;
    private final FileSource shared;
    private final FileSource appPrivate;
    private final BackupSink sink;
    private final Capabilities caps;
    private final EmulatorVersions versions;
    private final PackagePresence presence;

    private String appVersionName = "dev";
    private String deviceModel;
    private int androidSdk;
    private String registryOverrideSha256;

    public BackupRunner(TargetRegistry registry, ScanEngine scanner, PathResolver resolver,
                        FileSource shared, FileSource appPrivate, BackupSink sink,
                        Capabilities caps, EmulatorVersions versions, PackagePresence presence) {
        this.registry = registry;
        this.scanner = scanner;
        this.resolver = resolver;
        this.shared = shared;
        this.appPrivate = appPrivate;
        this.sink = sink;
        this.caps = caps;
        this.versions = versions;
        this.presence = presence;
    }

    public BackupRunner withDevice(String appVersionName, String deviceModel, int androidSdk) {
        this.appVersionName = appVersionName;
        this.deviceModel = deviceModel;
        this.androidSdk = androidSdk;
        return this;
    }

    public BackupRunner withRegistryOverride(String sha256) {
        this.registryOverrideSha256 = sha256;
        return this;
    }

    /**
     * @param selected ids to back up; null means every target enabled by default
     * @param kind     {@code "manual"}, {@code "scheduled"} or {@code "prerestore"}
     */
    public Result run(Collection<String> selected, String kind, long nowMs, Listener listener)
            throws IOException {

        Set<String> ids = new LinkedHashSet<>();
        if (selected == null) {
            for (Target t : registry.defaultEnabledTargets()) ids.add(t.id);
        } else {
            ids.addAll(selected);
        }
        // A save without the profile it belongs to is not a restorable backup, so coupled
        // targets are pulled in whether or not the caller remembered them.
        ids = registry.withCoupled(ids);

        BackupIndex index = loadIndex();
        Manifest prior = loadNewestManifest(index);
        VersionId version = VersionId.next(index.highestCounter(), nowMs);
        String vid = version.id();
        sink.ensureVersion(vid);

        List<ManifestTarget> manifestTargets = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        int archivedFiles = 0;
        long archivedBytes = 0;
        boolean cancelled = false;

        int idx = 0, count = ids.size();
        for (String id : ids) {
            if (listener.isCancelled()) { cancelled = true; break; }
            idx++;

            Target t = registry.target(id);
            Emulator e = registry.emulatorOf(id);
            listener.onProgress(new Progress(Progress.Phase.SCANNING, id, t.label, idx, count,
                    null, 0, 0, 0, 0, null));

            TargetScan scan = scanner.scan(e, t);
            if (scan.status.isProblem()) {
                problems.add(t.label + ": " + (scan.detail == null ? scan.status.name() : scan.detail));
            }
            if (!scan.hasContent()) {
                manifestTargets.add(emptyTarget(t, e, scan));
                continue;
            }

            FileSource src = t.tier == Tier.SHARED ? shared : appPrivate;
            final String root = scan.resolvedRoot;
            listener.onProgress(new Progress(Progress.Phase.DIFFING, id, t.label, idx, count,
                    null, 0, scan.fileCount(), 0, scan.totalBytes, null));

            ManifestTarget priorTarget = prior == null ? null : prior.target(id);
            Plan plan = DiffEngine.diff(id, scan.files,
                    priorTarget == null ? null : priorTarget.files,
                    rel -> {
                        try (InputStream in = src.open(root, rel)) {
                            return Hashes.sha256(in);
                        }
                    }, vid);

            int chainLen = priorTarget == null ? 0 : priorTarget.chain.size();
            ArchivePolicy.Decision decision = ArchivePolicy.decide(
                    priorTarget != null && !priorTarget.chain.isEmpty(),
                    chainLen + 1,
                    priorTarget == null ? 0 : priorTarget.baseFullBytes,
                    priorTarget == null ? 0 : priorTarget.chainIncBytes,
                    scan.fileCount(), plan.toArchive.size());

            if (decision.full) plan = DiffEngine.asFull(plan, scan.files, vid);

            if (!plan.unreadable.isEmpty()) {
                problems.add(t.label + ": " + plan.unreadable.size()
                        + " file(s) could not be read and are not in this backup");
            }

            if (plan.isNoOp() && priorTarget != null) {
                // Nothing changed. Carry the previous chain forward rather than writing an empty
                // archive, so unchanged targets cost nothing at all in a version.
                manifestTargets.add(unchangedTarget(t, e, scan, priorTarget, plan,
                        prior == null ? null : prior.version));
                continue;
            }

            String archiveName = id + (decision.full ? ".full.zip" : ".inc.zip");
            ArchiveWriter.Result written;
            final int fIdx = idx;
            try (OutputStream out = sink.createArchive(vid, archiveName)) {
                written = ArchiveWriter.write(out, root, src, plan, t.storeOnly(),
                        new ArchiveWriter.Listener() {
                            @Override public void onFile(String path, int i, int n, long bd, long bt) {
                                listener.onProgress(new Progress(Progress.Phase.ARCHIVING, id, t.label,
                                        fIdx, count, path, i, n, bd, bt, null));
                            }
                            @Override public boolean isCancelled() { return listener.isCancelled(); }
                        });
            } catch (IOException ex) {
                sink.discardArchive(vid, archiveName);
                throw ex;
            }

            if (written.cancelled) {
                sink.discardArchive(vid, archiveName);
                cancelled = true;
                break;
            }
            sink.commitArchive(vid, archiveName);
            archivedFiles += written.files;
            archivedBytes += written.archiveBytes;

            List<String> chain = new ArrayList<>();
            if (!decision.full && priorTarget != null) chain.addAll(priorTarget.chain);
            chain.add(vid + "/" + archiveName);

            long baseFull = decision.full ? written.archiveBytes
                    : (priorTarget == null ? 0 : priorTarget.baseFullBytes);
            long chainInc = decision.full ? 0
                    : (priorTarget == null ? 0 : priorTarget.chainIncBytes) + written.archiveBytes;

            List<String> skipped = new ArrayList<>(plan.unreadable);
            skipped.addAll(written.unreadable);
            if (!written.unreadable.isEmpty()) {
                problems.add(t.label + ": " + written.unreadable.size()
                        + " file(s) became unreadable while archiving");
            }

            manifestTargets.add(new ManifestTarget(id, e.id, t.tier, t.category, root,
                    TargetStatus.OK, decision.mode(),
                    decision.full ? null : (prior == null ? null : prior.version),
                    archiveName, written.sha256, written.archiveBytes, baseFull, chainInc, chain,
                    versionNameOf(e), versionCodeOf(e), plan.files, plan.deleted, skipped,
                    decision.reason));
        }

        listener.onProgress(Progress.of(Progress.Phase.WRITING_MANIFEST, "writing manifest"));

        Manifest manifest = new Manifest(vid, nowMs, appVersionName, registry.registryVersion(),
                registryOverrideSha256, deviceModel, androidSdk, resolver.extRoot(), caps,
                manifestTargets);

        if (cancelled) {
            // The version directory is left in place but never indexed, so it is invisible to the
            // app and replaced by the next run. Nothing partial is ever presented as a backup.
            return new Result(vid, manifest, true, archivedFiles, archivedBytes, problems);
        }

        sink.writeFile(vid, "manifest.json", manifest.toJson().getBytes(StandardCharsets.UTF_8));
        sink.writeFile(vid, "SHA256SUMS", RestoreScript.sha256sums(manifest).getBytes(StandardCharsets.UTF_8));
        sink.writeFile(vid, "RESTORE.txt", RestoreScript.versionReadme(manifest).getBytes(StandardCharsets.UTF_8));

        BackupIndex updated = index.with(new IndexEntry(vid, nowMs, manifest.totalBytes(),
                "prerestore".equals(kind), kind));
        sink.writeRootFile(BackupIndex.FILE_NAME, updated.toJson().getBytes(StandardCharsets.UTF_8));
        sink.writeRootFile("RESTORE.txt",
                RestoreScript.storeReadme(updated.versions()).getBytes(StandardCharsets.UTF_8));

        listener.onProgress(Progress.of(Progress.Phase.DONE, Sizes.human(archivedBytes) + " written"));
        return new Result(vid, manifest, false, archivedFiles, archivedBytes, problems);
    }

    // ------------------------------------------------------------------ helpers

    private BackupIndex loadIndex() {
        try {
            if (sink.hasRootFile(BackupIndex.FILE_NAME)) {
                return BackupIndex.fromJson(new String(
                        sink.readRootFile(BackupIndex.FILE_NAME), StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // Unreadable index. Rebuilt below from the manifests, which are the real record.
        }
        try {
            List<Manifest> all = new ArrayList<>();
            for (String v : sink.listVersions()) {
                if (!sink.hasFile(v, "manifest.json")) continue;
                try (InputStream in = sink.openFile(v, "manifest.json")) {
                    all.add(Manifest.fromJson(readAll(in)));
                } catch (Exception ignored) {
                }
            }
            return BackupIndex.rebuildFrom(all);
        } catch (Exception e) {
            return BackupIndex.empty();
        }
    }

    private Manifest loadNewestManifest(BackupIndex index) {
        List<IndexEntry> vs = index.versions();
        for (int i = vs.size() - 1; i >= 0; i--) {
            IndexEntry e = vs.get(i);
            // A pre-restore snapshot holds only the files a restore was about to overwrite, so it
            // is never a valid basis for the next incremental.
            if (e.isPreRestore()) continue;
            try (InputStream in = sink.openFile(e.id, "manifest.json")) {
                return Manifest.fromJson(readAll(in));
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private ManifestTarget emptyTarget(Target t, Emulator e, TargetScan scan) {
        return new ManifestTarget(t.id, e.id, t.tier, t.category,
                scan.resolvedRoot == null ? t.root : scan.resolvedRoot, scan.status, "none",
                null, null, null, 0, 0, 0, new ArrayList<>(),
                versionNameOf(e), versionCodeOf(e), new ArrayList<>(), new ArrayList<>(),
                new ArrayList<>(), scan.detail);
    }

    private ManifestTarget unchangedTarget(Target t, Emulator e, TargetScan scan,
                                           ManifestTarget prior, Plan plan, String basis) {
        return new ManifestTarget(t.id, e.id, t.tier, t.category, scan.resolvedRoot,
                TargetStatus.OK, "unchanged", basis, null, null, 0,
                prior.baseFullBytes, prior.chainIncBytes, prior.chain,
                versionNameOf(e), versionCodeOf(e), plan.files, plan.deleted, plan.unreadable,
                // "nothing changed" would be a lie when the reason nothing was written is that
                // the files could not be read at all.
                plan.unreadable.isEmpty()
                        ? "nothing changed"
                        : plan.unreadable.size() + " file(s) could not be read");
    }

    private String versionNameOf(Emulator e) {
        for (String p : e.packages) if (presence.isInstalled(p)) return versions.versionName(p);
        return null;
    }

    private long versionCodeOf(Emulator e) {
        for (String p : e.packages) if (presence.isInstalled(p)) return versions.versionCode(p);
        return -1;
    }

    static String readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
