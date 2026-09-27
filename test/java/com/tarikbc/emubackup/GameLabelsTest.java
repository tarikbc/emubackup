package com.tarikbc.emubackup;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The list sorts by when a game last changed, on the device or in a backup. */
class GameLabelsTest {

    private static final String REGISTRY =
            "{\"registryVersion\":1,\"emulators\":[{"
            + "\"id\":\"emu\",\"label\":\"Emu\",\"packages\":[\"a.b.c\"],\"targets\":["
            + "{\"id\":\"saves\",\"label\":\"Saves\",\"category\":\"SAVE\",\"tier\":\"SHARED\","
            + "\"root\":\"{EXT}/saves\",\"include\":[\"**\"],\"maxBytes\":104857600,"
            + "\"grouping\":{\"pattern\":\"{game}/**\",\"gameIdKind\":\"ROM_BASENAME\"}}"
            + "]}]}";

    private final TargetRegistry reg = TargetRegistry.parse(REGISTRY);

    private static void write(Path p, String body, long mtime) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, body);
        Files.setLastModifiedTime(p, FileTime.fromMillis(mtime));
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

    private static GameHistory.Entry find(Map<String, GameHistory.Entry> h, String game) {
        for (GameHistory.Entry e : h.values()) if (game.equals(e.group.gameKey)) return e;
        throw new AssertionError("no entry for " + game);
    }

    @Test
    @DisplayName("a backup that merely re-observed a game does not make it recent; a change does")
    void recencyFollowsChanges(@TempDir Path tmp) throws Exception {
        Path ext = tmp.resolve("device");
        LocalFolderSink sink = new LocalFolderSink(tmp.resolve("store").toString());
        write(ext.resolve("saves/zelda/a.dat"), "z1", 500L);
        write(ext.resolve("saves/mario/m.dat"), "m1", 500L);
        write(ext.resolve("saves/kart/k.dat"), "k1", 500L);
        runner(ext, sink).run(null, "manual", 1_000L, BackupRunner.SILENT);

        write(ext.resolve("saves/zelda/a.dat"), "z2", 600L);
        runner(ext, sink).run(null, "manual", 2_000L, BackupRunner.SILENT);
        // Played since the last backup: the device file is the newest thing about it.
        write(ext.resolve("saves/kart/k.dat"), "k2", 3_000L);

        Map<String, GameHistory.Entry> h = GameHistory.build(reg, index(sink),
                manifests(sink, index(sink)), scanNow(ext));

        assertEquals(2_000L, GameLabels.recencyMs(find(h, "zelda")), "changed in the second backup");
        assertEquals(1_000L, GameLabels.recencyMs(find(h, "mario")), "only ever changed in the first");
        assertEquals(3_000L, GameLabels.recencyMs(find(h, "kart")), "played after the last backup");
    }
}
