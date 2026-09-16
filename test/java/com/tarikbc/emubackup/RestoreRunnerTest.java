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
}
