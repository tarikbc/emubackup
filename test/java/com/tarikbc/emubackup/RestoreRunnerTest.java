package com.tarikbc.emubackup;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Restore is the one operation here that can destroy data, so these tests are mostly about what it
 * refuses to do.
 */
class RestoreRunnerTest {

    private static final String REGISTRY =
            "{\"registryVersion\":1,\"emulators\":[{"
            + "\"id\":\"emu\",\"label\":\"Emu\",\"packages\":[\"a.b.c\"],\"targets\":["
            + "{\"id\":\"saves\",\"label\":\"Saves\",\"category\":\"SAVE\",\"tier\":\"SHARED\","
            + "\"root\":\"{EXT}/saves\",\"include\":[\"**\"],\"maxBytes\":10485760}"
            + "]}]}";

    private static void write(Path p, String body) throws IOException {
        Files.createDirectories(p.getParent());
        Files.write(p, body.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static BackupRunner backupRunner(Path ext, LocalFolderSink sink) {
        TargetRegistry reg = TargetRegistry.parse(REGISTRY);
        PathResolver res = new PathResolver(ext.toString());
        FileSource src = new LocalFileSource();
        Capabilities caps = new Capabilities(true, false, false);
        return new BackupRunner(reg, new ScanEngine(res, src, null, caps, PackagePresence.ALL_PRESENT),
                res, src, null, sink, caps, EmulatorVersions.UNKNOWN, PackagePresence.ALL_PRESENT)
                .withDevice("1.0-test", "TestDevice", 33);
    }

    private static RestoreRunner restoreRunner(LocalFolderSink sink) {
        return new RestoreRunner(sink, new LocalFileSource(), null,
                new LocalFileSink(), null, new Capabilities(true, false, false));
    }

    /** Plans a restore of the whole target from a manifest, against the live tree. */
    private static RestorePlan planAll(Manifest m, Path ext, String targetId) throws IOException {
        ManifestTarget mt = m.target(targetId);
        LocalFileSource src = new LocalFileSource();
        List<FileStat> onDevice = src.exists(mt.root)
                ? src.walk(mt.root, true) : new ArrayList<>();
        return RestorePlanner.plan(mt, "Saves", onDevice, null, rel -> {
            try (InputStream in = src.open(mt.root, rel)) {
                return Hashes.sha256(in);
            }
        }, true);
    }

    @Test
    @DisplayName("a deleted save comes back, byte for byte")
    void restoresDeletedFiles(@TempDir Path tmp) throws Exception {
        Path ext = tmp.resolve("device");
        LocalFolderSink sink = new LocalFolderSink(tmp.resolve("store").toString());
        write(ext.resolve("saves/game1/save.dat"), "important progress");
        write(ext.resolve("saves/game2/save.dat"), "more progress");

        BackupRunner.Result b = backupRunner(ext, sink).run(null, "manual", 1_000L, BackupRunner.SILENT);

        Files.delete(ext.resolve("saves/game1/save.dat"));
        assertFalse(Files.exists(ext.resolve("saves/game1/save.dat")));

        RestoreRunner.Result r = restoreRunner(sink).run(b.manifest,
                Arrays.asList(planAll(b.manifest, ext, "saves")), 2_000L, RestoreRunner.SILENT);

        assertTrue(r.ok(), "restore reported problems: " + r.missing + r.corrupt + r.failures);
        assertEquals("important progress", read(ext.resolve("saves/game1/save.dat")));
        assertEquals(1, r.filesWritten, "the untouched file should have been skipped as identical");
    }

    @Test
    @DisplayName("what is about to be overwritten is captured first, in a pinned snapshot")
    void preRestoreSnapshot(@TempDir Path tmp) throws Exception {
        Path ext = tmp.resolve("device");
        Path store = tmp.resolve("store");
        LocalFolderSink sink = new LocalFolderSink(store.toString());
        write(ext.resolve("saves/a.dat"), "original");

        BackupRunner.Result b = backupRunner(ext, sink).run(null, "manual", 1_000L, BackupRunner.SILENT);

        // Something the user would not want to lose, written after the backup but older than it
        // by mtime so it is an ordinary overwrite rather than a conflict.
        write(ext.resolve("saves/a.dat"), "work done since the backup");
        ext.resolve("saves/a.dat").toFile().setLastModified(500L);

        RestoreRunner.Result r = restoreRunner(sink).run(b.manifest,
                Arrays.asList(planAll(b.manifest, ext, "saves")), 2_000L, RestoreRunner.SILENT);

        assertNotNull(r.snapshotVersionId, "nothing was snapshotted before overwriting");
        assertTrue(r.snapshotVersionId.endsWith("-prerestore"));
        assertEquals("original", read(ext.resolve("saves/a.dat")), "the restore itself did not apply");

        // The snapshot must hold the content that was just replaced, not the content restored.
        Path snapDir = store.resolve(r.snapshotVersionId);
        assertTrue(Files.isDirectory(snapDir));
        assertTrue(Files.isRegularFile(snapDir.resolve("manifest.json")));
        Manifest snap = Manifest.fromJson(read(snapDir.resolve("manifest.json")));
        assertEquals(1, snap.target("saves").files.size());
        assertEquals(Hashes.sha256("work done since the backup".getBytes(StandardCharsets.UTF_8)),
                snap.target("saves").files.get(0).sha256,
                "the snapshot captured the wrong content");

        // And it is pinned, so retention can never prune the only copy of that work.
        BackupIndex idx = BackupIndex.fromJson(read(store.resolve("index.json")));
        boolean found = false;
        for (IndexEntry e : idx.versions()) {
            if (e.id.equals(r.snapshotVersionId)) {
                found = true;
                assertTrue(e.pinned, "the pre-restore snapshot must be pinned");
                assertTrue(e.isPreRestore());
            }
        }
        assertTrue(found, "the snapshot is not in the index");
    }

    @Test
    @DisplayName("a file newer on the device survives a restore that did not force it")
    void newerFilesAreNotClobbered(@TempDir Path tmp) throws Exception {
        Path ext = tmp.resolve("device");
        LocalFolderSink sink = new LocalFolderSink(tmp.resolve("store").toString());
        write(ext.resolve("saves/a.dat"), "old");
        ext.resolve("saves/a.dat").toFile().setLastModified(1_000L);

        BackupRunner.Result b = backupRunner(ext, sink).run(null, "manual", 1_000L, BackupRunner.SILENT);

        write(ext.resolve("saves/a.dat"), "THIS WEEK'S PROGRESS");
        ext.resolve("saves/a.dat").toFile().setLastModified(9_000_000L);

        RestorePlan p = planAll(b.manifest, ext, "saves");
        assertEquals(RestoreAction.CONFLICT_NEWER, p.items.get(0).action);

        RestoreRunner.Result r = restoreRunner(sink).run(b.manifest, Arrays.asList(p),
                2_000L, RestoreRunner.SILENT);

        assertEquals(0, r.filesWritten);
        assertNull(r.snapshotVersionId, "nothing was overwritten, so nothing needed snapshotting");
        assertEquals("THIS WEEK'S PROGRESS", read(ext.resolve("saves/a.dat")));

        // Forcing it does apply, and snapshots the work first.
        RestoreRunner.Result forced = restoreRunner(sink).run(b.manifest,
                Arrays.asList(p.withForced(true)), 3_000L, RestoreRunner.SILENT);
        assertEquals(1, forced.filesWritten);
        assertEquals("old", read(ext.resolve("saves/a.dat")));
        assertNotNull(forced.snapshotVersionId, "forcing must still snapshot what it replaces");
    }

    @Test
    @DisplayName("a file whose content does not match the manifest is abandoned, leaving the original")
    void corruptArchiveLeavesTheOriginalIntact(@TempDir Path tmp) throws Exception {
        Path ext = tmp.resolve("device");
        Path store = tmp.resolve("store");
        LocalFolderSink sink = new LocalFolderSink(store.toString());
        write(ext.resolve("saves/a.dat"), "backed up");

        BackupRunner.Result b = backupRunner(ext, sink).run(null, "manual", 1_000L, BackupRunner.SILENT);

        // Corrupt the record rather than the zip, so extraction succeeds and only verification
        // fails — which is the case that would otherwise write bad bytes over a good save.
        Path mPath = store.resolve(b.versionId).resolve("manifest.json");
        String json = read(mPath).replace(
                b.manifest.target("saves").files.get(0).sha256,
                "0000000000000000000000000000000000000000000000000000000000000000");
        Files.write(mPath, json.getBytes(StandardCharsets.UTF_8));
        Manifest tampered = Manifest.fromJson(read(mPath));

        write(ext.resolve("saves/a.dat"), "current content");
        ext.resolve("saves/a.dat").toFile().setLastModified(500L);

        RestoreRunner.Result r = restoreRunner(sink).run(tampered,
                Arrays.asList(planAll(tampered, ext, "saves")), 2_000L, RestoreRunner.SILENT);

        assertFalse(r.ok());
        assertEquals(Arrays.asList("a.dat"), r.corrupt);
        assertEquals(0, r.filesWritten);
        assertEquals("current content", read(ext.resolve("saves/a.dat")),
                "the original must survive a failed verification");
        assertFalse(Files.exists(ext.resolve("saves/a.dat.ebtmp")), "the temp file should be gone");
    }

    @Test
    @DisplayName("restoring from an incremental pulls each file from whichever archive holds it")
    void restoresAcrossAChain(@TempDir Path tmp) throws Exception {
        Path ext = tmp.resolve("device");
        LocalFolderSink sink = new LocalFolderSink(tmp.resolve("store").toString());
        for (int i = 1; i <= 8; i++) write(ext.resolve("saves/f" + i + ".dat"), "v1-" + i);

        backupRunner(ext, sink).run(null, "manual", 1_000L, BackupRunner.SILENT);
        write(ext.resolve("saves/f1.dat"), "v2-1");
        BackupRunner.Result b2 = backupRunner(ext, sink).run(null, "manual", 2_000L, BackupRunner.SILENT);
        assertEquals("incremental", b2.manifest.target("saves").mode);
        assertEquals(2, b2.manifest.target("saves").chain.size());

        // Wipe the lot, then restore from the newest version only.
        for (int i = 1; i <= 8; i++) Files.delete(ext.resolve("saves/f" + i + ".dat"));

        RestoreRunner.Result r = restoreRunner(sink).run(b2.manifest,
                Arrays.asList(planAll(b2.manifest, ext, "saves")), 3_000L, RestoreRunner.SILENT);

        assertTrue(r.ok(), "" + r.missing + r.corrupt + r.failures);
        assertEquals(8, r.filesWritten);
        assertEquals("v2-1", read(ext.resolve("saves/f1.dat")), "the changed file came from the increment");
        assertEquals("v1-2", read(ext.resolve("saves/f2.dat")), "the rest came from the base full");
    }

    @Test
    @DisplayName("progress says what it is doing: the safety copy by name, the archive being read, the file written")
    void progressIsDescriptive(@TempDir Path tmp) throws Exception {
        Path ext = tmp.resolve("device");
        LocalFolderSink sink = new LocalFolderSink(tmp.resolve("store").toString());
        write(ext.resolve("saves/game1/save.dat"), "important progress");
        BackupRunner.Result b = backupRunner(ext, sink).run(null, "manual", 1_000L, BackupRunner.SILENT);
        write(ext.resolve("saves/game1/save.dat"), "overwritten since");
        ext.resolve("saves/game1/save.dat").toFile().setLastModified(500L);

        List<Progress> seen = new ArrayList<>();
        RestoreRunner.Listener listener = new RestoreRunner.Listener() {
            @Override public void onProgress(Progress p) { seen.add(p); }
            @Override public boolean isCancelled() { return false; }
        };
        restoreRunner(sink).run(b.manifest, Arrays.asList(planAll(b.manifest, ext, "saves")), 2_000L, listener);

        boolean safety = false, reading = false, writing = false;
        for (Progress p : seen) {
            if (p.message != null && p.message.contains("safety copy") && p.message.contains("save.dat")) safety = true;
            if (p.message != null && p.message.startsWith("Reading the backup") && p.bytesDone > 0) reading = true;
            if (p.message != null && p.message.startsWith("Writing") && "game1/save.dat".equals(p.fileName)) writing = true;
        }
        assertTrue(safety, "the safety copy should name the file it saves: " + messages(seen));
        assertTrue(reading, "reading the archive should report bytes read: " + messages(seen));
        assertTrue(writing, "writing a file should say so, with the file: " + messages(seen));
    }

    private static String messages(List<Progress> seen) {
        StringBuilder b = new StringBuilder();
        for (Progress p : seen) b.append(p.phase).append(' ').append(p.message).append(' ').append(p.fileName)
                .append(' ').append(p.bytesDone).append("; ");
        return b.toString();
    }

    @Test
    @DisplayName("a store whose index cannot be read refuses the restore rather than starting a new counter")
    void unreadableIndexRefusesTheRestore(@TempDir Path tmp) throws Exception {
        Path ext = tmp.resolve("device");
        Path store = tmp.resolve("store");
        LocalFolderSink good = new LocalFolderSink(store.toString());
        write(ext.resolve("saves/a.dat"), "original");
        BackupRunner.Result b = backupRunner(ext, good).run(null, "manual", 1_000L, BackupRunner.SILENT);
        write(ext.resolve("saves/a.dat"), "changed since");
        ext.resolve("saves/a.dat").toFile().setLastModified(500L);

        // The archives are there; only the index is out of reach, the way a stalled Drive listing is.
        BackupSink flaky = (BackupSink) java.lang.reflect.Proxy.newProxyInstance(
                BackupSink.class.getClassLoader(), new Class<?>[] { BackupSink.class },
                (proxy, method, args) -> {
                    boolean index = args != null && args.length > 0 && BackupIndex.FILE_NAME.equals(args[0]);
                    if (index && method.getName().equals("hasRootFile")) return false;
                    if (index && method.getName().equals("readRootFile")) throw new IOException("timeout");
                    try {
                        return method.invoke(good, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        RestoreRunner runner = new RestoreRunner(flaky, new LocalFileSource(), null,
                new LocalFileSink(), null, new Capabilities(true, false, false));
        RestorePlan plan = planAll(b.manifest, ext, "saves").withForced(true);

        IOException refused = assertThrows(IOException.class,
                () -> runner.run(b.manifest, Arrays.asList(plan), 2_000L, RestoreRunner.SILENT));
        assertTrue(refused.getMessage().contains("list of backups"), refused.getMessage());
        assertEquals("changed since", read(ext.resolve("saves/a.dat")), "nothing may be written");
        for (String v : good.listVersions()) {
            assertFalse(v.startsWith("v0001-") && v.endsWith("-prerestore"), "no counter restarted at v0001: " + v);
        }
        assertEquals(1, index(good).size(), "the index is untouched");
    }

    private static List<IndexEntry> index(LocalFolderSink sink) throws IOException {
        return BackupIndex.fromJson(new String(sink.readRootFile(BackupIndex.FILE_NAME),
                java.nio.charset.StandardCharsets.UTF_8)).versions();
    }

    /** A sink that behaves like {@code good} except where {@code rule} says otherwise. */
    private static BackupSink proxy(BackupSink good, java.util.function.BiFunction<String, Object[], Object> rule) {
        return (BackupSink) java.lang.reflect.Proxy.newProxyInstance(
                BackupSink.class.getClassLoader(), new Class<?>[] { BackupSink.class },
                (proxyObj, method, args) -> {
                    Object r = rule.apply(method.getName(), args == null ? new Object[0] : args);
                    if (r instanceof IOException) throw (IOException) r;
                    if (r != null) return r;
                    try {
                        return method.invoke(good, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    @Test
    @DisplayName("a safety copy that cannot be written leaves no half-made version behind")
    void failedSafetyCopyLeavesNoFolder(@TempDir Path tmp) throws Exception {
        Path ext = tmp.resolve("device");
        LocalFolderSink good = new LocalFolderSink(tmp.resolve("store").toString());
        write(ext.resolve("saves/a.dat"), "original");
        BackupRunner.Result b = backupRunner(ext, good).run(null, "manual", 1_000L, BackupRunner.SILENT);
        write(ext.resolve("saves/a.dat"), "changed since");
        ext.resolve("saves/a.dat").toFile().setLastModified(500L);

        BackupSink flaky = proxy(good, (name, args) ->
                name.equals("createArchive") && String.valueOf(args[0]).endsWith("-prerestore")
                        ? new IOException("timeout") : null);
        RestoreRunner runner = new RestoreRunner(flaky, new LocalFileSource(), null,
                new LocalFileSink(), null, new Capabilities(true, false, false));
        assertThrows(IOException.class, () -> runner.run(b.manifest,
                Arrays.asList(planAll(b.manifest, ext, "saves").withForced(true)), 2_000L, RestoreRunner.SILENT));

        for (String v : good.listVersions()) assertFalse(v.endsWith("-prerestore"), "left behind: " + v);
        assertEquals("changed since", read(ext.resolve("saves/a.dat")));
    }

    @Test
    @DisplayName("an archive the store cannot serve is read from a local copy with the same checksum")
    void restoresFromALocalMirror(@TempDir Path tmp) throws Exception {
        Path ext = tmp.resolve("device");
        LocalFolderSink good = new LocalFolderSink(tmp.resolve("store").toString());
        write(ext.resolve("saves/game1/save.dat"), "important progress");
        BackupRunner.Result b = backupRunner(ext, good).run(null, "manual", 1_000L, BackupRunner.SILENT);

        // A second store holding the same archives under other version ids, as a device
        // folder does beside Drive.
        Path mirrorRoot = tmp.resolve("mirror");
        String v = b.versionId;
        Files.createDirectories(mirrorRoot.resolve(v));
        for (String f : new String[] { "saves.full.zip", "manifest.json" }) {
            Files.copy(tmp.resolve("store").resolve(v).resolve(f), mirrorRoot.resolve(v).resolve(f));
        }
        Files.delete(ext.resolve("saves/game1/save.dat"));

        // The main store answers everything except archives, like Drive on a stalling link.
        BackupSink remote = proxy(good, (name, args) ->
                name.equals("openFile") && String.valueOf(args[1]).endsWith(".zip") ? new IOException("timeout") : null);
        ArchiveMirror mirror = new ArchiveMirror(remote, ArchiveMirror.scan(mirrorRoot.toFile()), version -> {
            try (InputStream in = good.openFile(version, "manifest.json")) {
                return Manifest.fromJson(BackupRunner.readAll(in));
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        List<Progress> seen = new ArrayList<>();
        RestoreRunner runner = new RestoreRunner(remote, new LocalFileSource(), null,
                new LocalFileSink(), null, new Capabilities(true, false, false)).withArchiveOpener(mirror);
        RestoreRunner.Result r = runner.run(b.manifest, Arrays.asList(planAll(b.manifest, ext, "saves")), 2_000L,
                new RestoreRunner.Listener() {
                    @Override public void onProgress(Progress p) { seen.add(p); }
                    @Override public boolean isCancelled() { return false; }
                });

        assertTrue(r.ok(), "restore reported problems: " + r.missing + r.corrupt + r.failures);
        assertEquals("important progress", read(ext.resolve("saves/game1/save.dat")));
        boolean said = false;
        for (Progress p : seen) if (p.message != null && p.message.contains("copy on this device")) said = true;
        assertTrue(said, "the person should be told the copy on the device was used: " + messages(seen));
    }

    @Test
    @DisplayName("a local copy whose bytes do not match the checksum is not trusted")
    void mirrorRejectsACorruptCopy(@TempDir Path tmp) throws Exception {
        Path ext = tmp.resolve("device");
        LocalFolderSink good = new LocalFolderSink(tmp.resolve("store").toString());
        write(ext.resolve("saves/game1/save.dat"), "important progress");
        BackupRunner.Result b = backupRunner(ext, good).run(null, "manual", 1_000L, BackupRunner.SILENT);
        Path mirrorRoot = tmp.resolve("mirror");
        Files.createDirectories(mirrorRoot.resolve(b.versionId));
        Files.copy(tmp.resolve("store").resolve(b.versionId).resolve("manifest.json"),
                mirrorRoot.resolve(b.versionId).resolve("manifest.json"));
        Files.write(mirrorRoot.resolve(b.versionId).resolve("saves.full.zip"), "not a zip".getBytes(StandardCharsets.UTF_8));
        Files.delete(ext.resolve("saves/game1/save.dat"));

        ArchiveMirror mirror = new ArchiveMirror(good, ArchiveMirror.scan(mirrorRoot.toFile()), version -> {
            try (InputStream in = good.openFile(version, "manifest.json")) {
                return Manifest.fromJson(BackupRunner.readAll(in));
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        RestoreRunner runner = new RestoreRunner(good, new LocalFileSource(), null,
                new LocalFileSink(), null, new Capabilities(true, false, false)).withArchiveOpener(mirror);
        RestoreRunner.Result r = runner.run(b.manifest, Arrays.asList(planAll(b.manifest, ext, "saves")), 2_000L, RestoreRunner.SILENT);
        assertTrue(r.ok(), "the store's own copy should have been used instead: " + r.missing + r.corrupt);
        assertEquals("important progress", read(ext.resolve("saves/game1/save.dat")));
    }

    @Test
    @DisplayName("a local archive that holds the same file, under another backup, serves it")
    void mirrorMatchesByFileNotByArchive(@TempDir Path tmp) throws Exception {
        Path ext = tmp.resolve("device");
        LocalFolderSink good = new LocalFolderSink(tmp.resolve("store").toString());
        write(ext.resolve("saves/game1/save.dat"), "important progress");
        BackupRunner.Result b = backupRunner(ext, good).run(null, "manual", 1_000L, BackupRunner.SILENT);

        // A separate store, backed up later with one more file: a different archive with
        // different bytes and a different checksum, holding the same save.dat.
        write(ext.resolve("saves/game2/other.dat"), "unrelated");
        LocalFolderSink mirror = new LocalFolderSink(tmp.resolve("mirror").toString());
        backupRunner(ext, mirror).run(null, "manual", 5_000L, BackupRunner.SILENT);
        Files.delete(ext.resolve("saves/game1/save.dat"));

        BackupSink remote = proxy(good, (name, args) ->
                name.equals("openFile") && String.valueOf(args[1]).endsWith(".zip") ? new IOException("timeout") : null);
        ArchiveMirror m = new ArchiveMirror(remote, ArchiveMirror.scan(tmp.resolve("mirror").toFile()), version -> null);
        RestoreRunner runner = new RestoreRunner(remote, new LocalFileSource(), null,
                new LocalFileSink(), null, new Capabilities(true, false, false)).withArchiveOpener(m);
        RestoreRunner.Result r = runner.run(b.manifest, Arrays.asList(planAll(b.manifest, ext, "saves")), 2_000L, RestoreRunner.SILENT);
        assertTrue(r.ok(), "restore reported problems: " + r.missing + r.corrupt + r.failures);
        assertEquals("important progress", read(ext.resolve("saves/game1/save.dat")));
    }

    @Test
    @DisplayName("the safety copy can go to a nearer store than the one being restored from")
    void safetyCopyGoesToTheNearerStore(@TempDir Path tmp) throws Exception {
        Path ext = tmp.resolve("device");
        LocalFolderSink remote = new LocalFolderSink(tmp.resolve("store").toString());
        LocalFolderSink local = new LocalFolderSink(tmp.resolve("local").toString());
        write(ext.resolve("saves/a.dat"), "original");
        BackupRunner.Result b = backupRunner(ext, remote).run(null, "manual", 1_000L, BackupRunner.SILENT);
        write(ext.resolve("saves/a.dat"), "changed since");
        ext.resolve("saves/a.dat").toFile().setLastModified(500L);

        RestoreRunner runner = new RestoreRunner(remote, new LocalFileSource(), null,
                new LocalFileSink(), null, new Capabilities(true, false, false)).withSafetyCopySink(local);
        RestoreRunner.Result r = runner.run(b.manifest,
                Arrays.asList(planAll(b.manifest, ext, "saves").withForced(true)), 2_000L, RestoreRunner.SILENT);

        assertTrue(r.ok());
        assertEquals("original", read(ext.resolve("saves/a.dat")));
        assertNotNull(r.snapshotVersionId);
        assertTrue(r.snapshotVersionId.endsWith("-prerestore"));
        assertTrue(local.listVersions().contains(r.snapshotVersionId), "the safety copy lives in the local store");
        assertFalse(remote.listVersions().contains(r.snapshotVersionId), "and not in the store restored from");
        assertEquals(1, index(local).size(), "the local index lists it");
        assertTrue(index(local).get(0).isPreRestore());
        assertEquals(1, index(remote).size(), "the remote index is untouched");
    }
}
