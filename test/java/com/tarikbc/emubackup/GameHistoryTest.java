package com.tarikbc.emubackup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Built on real backups written by BackupRunner into a local store, so the history is read
 * from exactly the manifests the app produces, not from hand-made fixtures.
 */
class GameHistoryTest {

    /** One target grouped by the first path segment, so "zelda/..." and "mario/..." are games. */
    private static final String REGISTRY =
            "{\"registryVersion\":1,\"emulators\":[{"
            + "\"id\":\"emu\",\"label\":\"Emu\",\"packages\":[\"a.b.c\"],\"targets\":["
            + "{\"id\":\"saves\",\"label\":\"Saves\",\"category\":\"SAVE\",\"tier\":\"SHARED\","
            + "\"root\":\"{EXT}/saves\",\"include\":[\"**\"],\"maxBytes\":104857600,"
            + "\"grouping\":{\"pattern\":\"{game}/**\",\"gameIdKind\":\"ROM_BASENAME\"}}"
            + "]}]}";

    private final TargetRegistry reg = TargetRegistry.parse(REGISTRY);

    private static void write(Path p, String body) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, body);
    }

    private BackupRunner runner(Path ext, LocalFolderSink sink) {
        PathResolver res = new PathResolver(ext.toString());
        FileSource src = new LocalFileSource();
        Capabilities caps = new Capabilities(true, false, false);
        ScanEngine scan = new ScanEngine(res, src, null, caps, PackagePresence.ALL_PRESENT);
        return new BackupRunner(reg, scan, res, src, null, sink, caps,
                EmulatorVersions.UNKNOWN, PackagePresence.ALL_PRESENT);
    }

    private List<TargetScan> scanNow(Path ext) {
        PathResolver res = new PathResolver(ext.toString());
        ScanEngine scan = new ScanEngine(res, new LocalFileSource(), null,
                new Capabilities(true, false, false), PackagePresence.ALL_PRESENT);
        List<TargetScan> out = new ArrayList<>();
        for (Emulator e : reg.emulators()) out.addAll(scan.scanAll(e, e.targets));
        return out;
    }

    private static Map<String, Manifest> manifests(LocalFolderSink sink, List<IndexEntry> index)
            throws IOException {
        Map<String, Manifest> out = new HashMap<>();
        for (IndexEntry e : index) {
            try (java.io.InputStream in = sink.openFile(e.id, "manifest.json")) {
                out.put(e.id, Manifest.fromJson(BackupRunner.readAll(in)));
            }
        }
        return out;
    }

    private static List<IndexEntry> index(LocalFolderSink sink) throws IOException {
        return BackupIndex.fromJson(new String(sink.readRootFile(BackupIndex.FILE_NAME),
                java.nio.charset.StandardCharsets.UTF_8)).versions();
    }

    @Test
    @DisplayName("a game that changed shows a snapshot; one that did not shows only its first")
    void snapshotsFollowChanges(@TempDir Path tmp) throws Exception {
        Path ext = tmp.resolve("device");
        LocalFolderSink sink = new LocalFolderSink(tmp.resolve("store").toString());
        write(ext.resolve("saves/zelda/a.dat"), "z1");
        write(ext.resolve("saves/mario/m.dat"), "m1");
        String v1 = runner(ext, sink).run(null, "manual", 1_000L, BackupRunner.SILENT).versionId;

        write(ext.resolve("saves/zelda/a.dat"), "z2");
        String v2 = runner(ext, sink).run(null, "manual", 2_000L, BackupRunner.SILENT).versionId;

        Map<String, GameHistory.Entry> h = GameHistory.build(reg, index(sink), manifests(sink, index(sink)), scanNow(ext));

        GameHistory.Entry zelda = find(h, "zelda");
        assertEquals(2, zelda.snapshots.size(), "zelda changed once after its first backup");
        assertEquals(v2, zelda.snapshots.get(0).versionId);
        assertEquals(1, zelda.snapshots.get(0).filesChanged);
        assertFalse(zelda.snapshots.get(0).first);
        assertEquals(v1, zelda.snapshots.get(1).versionId);
        assertTrue(zelda.snapshots.get(1).first);
        assertEquals(2_000L, zelda.lastBackedUpMs);
        assertFalse(zelda.changedSinceBackup);
        assertTrue(zelda.onDevice);

        GameHistory.Entry mario = find(h, "mario");
        assertEquals(1, mario.snapshots.size(), "mario never changed after v1");
        assertEquals(v1, mario.snapshots.get(0).versionId);
        // Still counts as backed up in v2, because v2 observed it unchanged.
        assertEquals(2_000L, mario.lastBackedUpMs);
    }

    @Test
    @DisplayName("a full rebase is not a change")
    void rebaseIsNotAChange(@TempDir Path tmp) throws Exception {
        Path ext = tmp.resolve("device");
        LocalFolderSink sink = new LocalFolderSink(tmp.resolve("store").toString());
        write(ext.resolve("saves/zelda/a.dat"), "z1");
        write(ext.resolve("saves/mario/m.dat"), "m1");
        runner(ext, sink).run(null, "manual", 1_000L, BackupRunner.SILENT);

        // Nine more runs, changing only mario, so ArchivePolicy forces a rebase of the target.
        // The rebase restamps zelda's file to the new version although its bytes never changed.
        for (int i = 2; i <= 10; i++) {
            write(ext.resolve("saves/mario/m.dat"), "m" + i);
            runner(ext, sink).run(null, "manual", i * 1_000L, BackupRunner.SILENT);
        }
        Map<String, GameHistory.Entry> h = GameHistory.build(reg, index(sink), manifests(sink, index(sink)), scanNow(ext));
        assertEquals(1, find(h, "zelda").snapshots.size(),
                "zelda was counted as changed by a rebase that only restamped it");
        assertEquals(10, find(h, "mario").snapshots.size());
    }

    @Test
    @DisplayName("editing a save after the backup reads as changed since")
    void changedSince(@TempDir Path tmp) throws Exception {
        Path ext = tmp.resolve("device");
        LocalFolderSink sink = new LocalFolderSink(tmp.resolve("store").toString());
        write(ext.resolve("saves/zelda/a.dat"), "z1");
        runner(ext, sink).run(null, "manual", 1_000L, BackupRunner.SILENT);
        write(ext.resolve("saves/zelda/a.dat"), "z1-edited");
        Files.setLastModifiedTime(ext.resolve("saves/zelda/a.dat"),
                java.nio.file.attribute.FileTime.fromMillis(9_999_000L));

        Map<String, GameHistory.Entry> h = GameHistory.build(reg, index(sink), manifests(sink, index(sink)), scanNow(ext));
        assertTrue(find(h, "zelda").changedSinceBackup);
    }

    @Test
    @DisplayName("a game never backed up is on the device with no history")
    void neverBackedUp(@TempDir Path tmp) throws Exception {
        Path ext = tmp.resolve("device");
        LocalFolderSink sink = new LocalFolderSink(tmp.resolve("store").toString());
        write(ext.resolve("saves/zelda/a.dat"), "z1");
        runner(ext, sink).run(null, "manual", 1_000L, BackupRunner.SILENT);
        write(ext.resolve("saves/newgame/n.dat"), "n1");

        Map<String, GameHistory.Entry> h = GameHistory.build(reg, index(sink), manifests(sink, index(sink)), scanNow(ext));
        GameHistory.Entry n = find(h, "newgame");
        assertTrue(n.onDevice);
        assertTrue(n.snapshots.isEmpty());
        assertEquals(0, n.lastBackedUpMs);
        assertTrue(n.changedSinceBackup);
    }

    @Test
    @DisplayName("a deleted game is a change, and stays in history off the device")
    void deletionIsAChange(@TempDir Path tmp) throws Exception {
        Path ext = tmp.resolve("device");
        LocalFolderSink sink = new LocalFolderSink(tmp.resolve("store").toString());
        write(ext.resolve("saves/zelda/a.dat"), "z1");
        write(ext.resolve("saves/mario/m.dat"), "m1");
        runner(ext, sink).run(null, "manual", 1_000L, BackupRunner.SILENT);
        Files.delete(ext.resolve("saves/mario/m.dat"));
        Files.delete(ext.resolve("saves/mario"));
        String v2 = runner(ext, sink).run(null, "manual", 2_000L, BackupRunner.SILENT).versionId;

        Map<String, GameHistory.Entry> h = GameHistory.build(reg, index(sink), manifests(sink, index(sink)), scanNow(ext));
        GameHistory.Entry mario = find(h, "mario");
        assertFalse(mario.onDevice);
        assertEquals(2, mario.snapshots.size());
        assertEquals(v2, mario.snapshots.get(0).versionId);
        assertEquals(1, mario.snapshots.get(0).filesChanged);
        assertNotNull(mario.group, "a game gone from the device still shows as it was backed up");
    }

    @Test
    @DisplayName("a safety copy is listed separately, not as a change")
    void safetyCopiesAreSeparate(@TempDir Path tmp) throws Exception {
        Path ext = tmp.resolve("device");
        LocalFolderSink sink = new LocalFolderSink(tmp.resolve("store").toString());
        write(ext.resolve("saves/zelda/a.dat"), "z1");
        String v1 = runner(ext, sink).run(null, "manual", 1_000L, BackupRunner.SILENT).versionId;
        write(ext.resolve("saves/zelda/a.dat"), "z2");
        runner(ext, sink).run(null, "manual", 2_000L, BackupRunner.SILENT);

        // Restore v1 over the device, which writes a pinned pre-restore snapshot of "z2". The
        // device copy is dated older than the backup so the plan applies without forcing.
        ext.resolve("saves/zelda/a.dat").toFile().setLastModified(500L);
        Manifest m1;
        try (java.io.InputStream in = sink.openFile(v1, "manifest.json")) {
            m1 = Manifest.fromJson(BackupRunner.readAll(in));
        }
        LocalFileSource src = new LocalFileSource();
        List<RestorePlan> plans = new ArrayList<>();
        for (ManifestTarget mt : m1.targets) {
            List<FileStat> onDevice = src.exists(mt.root) ? src.walk(mt.root, true) : new ArrayList<>();
            plans.add(RestorePlanner.plan(mt, mt.id, onDevice, null, rel -> {
                try (java.io.InputStream in = src.open(mt.root, rel)) {
                    return Hashes.sha256(in);
                }
            }, true));
        }
        new RestoreRunner(sink, src, null, new LocalFileSink(), null,
                new Capabilities(true, false, false))
                .run(m1, plans, 3_000L, RestoreRunner.SILENT);

        Map<String, GameHistory.Entry> h = GameHistory.build(reg, index(sink), manifests(sink, index(sink)), scanNow(ext));
        GameHistory.Entry zelda = find(h, "zelda");
        assertEquals(1, zelda.safetyCopies.size(), "the pre-restore snapshot was not listed");
        assertTrue(zelda.safetyCopies.get(0).endsWith("-prerestore"));
        for (GameHistory.Snapshot s : zelda.snapshots) {
            assertFalse(s.versionId.endsWith("-prerestore"), "a safety copy leaked into the change list");
        }
    }

    private static GameHistory.Entry find(Map<String, GameHistory.Entry> h, String game) {
        for (GameHistory.Entry e : h.values()) if (game.equals(e.group.gameKey)) return e;
        throw new AssertionError("no entry for " + game + " in " + h.keySet());
    }
}
