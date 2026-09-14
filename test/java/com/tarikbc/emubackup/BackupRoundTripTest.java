package com.tarikbc.emubackup;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The no-lock-in promise, as an executable assertion.
 *
 * <p>A backup is written, then restored twice: once through this app's own code, and once using
 * nothing but {@code java.util.zip} following the instructions in the generated
 * {@code RESTORE.txt}. Both must reproduce the original tree byte for byte. If the second one
 * ever fails, the promise in {@code FORMAT.md} section 1 is broken, whatever the app itself can
 * still do.
 */
class BackupRoundTripTest {

    private static final String REGISTRY =
            "{\"registryVersion\":1,\"emulators\":[{"
            + "\"id\":\"emu\",\"label\":\"Emu\",\"packages\":[\"a.b.c\"],\"targets\":["
            + "{\"id\":\"saves\",\"label\":\"Saves\",\"category\":\"SAVE\",\"tier\":\"SHARED\","
            + "\"root\":\"{EXT}/saves\",\"include\":[\"**\"],\"maxBytes\":104857600},"
            + "{\"id\":\"cards\",\"label\":\"Cards\",\"category\":\"SAVE\",\"tier\":\"SHARED\","
            + "\"root\":\"{EXT}/cards\",\"include\":[\"**\"],\"maxBytes\":104857600}"
            + "]}]}";

    private static void write(Path p, String body) throws IOException {
        Files.createDirectories(p.getParent());
        Files.write(p, body.getBytes(StandardCharsets.UTF_8));
    }

    private static BackupRunner runner(Path ext, LocalFolderSink sink) {
        TargetRegistry reg = TargetRegistry.parse(REGISTRY);
        PathResolver res = new PathResolver(ext.toString());
        FileSource src = new LocalFileSource();
        Capabilities caps = new Capabilities(true, false, false);
        ScanEngine scan = new ScanEngine(res, src, null, caps, PackagePresence.ALL_PRESENT);
        return new BackupRunner(reg, scan, res, src, null, sink, caps,
                EmulatorVersions.UNKNOWN, PackagePresence.ALL_PRESENT)
                .withDevice("1.0-test", "TestDevice", 33);
    }

    /** Every file under a directory, as path -> content. */
    private static Map<String, String> snapshot(Path root) throws IOException {
        Map<String, String> out = new HashMap<>();
        if (!Files.isDirectory(root)) return out;
        final Path base = root;
        Files.walk(root).filter(Files::isRegularFile).forEach(p -> {
            try {
                out.put(base.relativize(p).toString().replace(File.separatorChar, '/'),
                        new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        return out;
    }

    /**
     * Restores using only java.util.zip, following the archives named in RESTORE.txt in the order
     * it lists them. Nothing from the app's own restore path is involved.
     */
    private static void handRestore(Path storeRoot, String versionId, String targetId, Path into)
            throws IOException {
        String readme = new String(
                Files.readAllBytes(storeRoot.resolve(versionId).resolve("RESTORE.txt")),
                StandardCharsets.UTF_8);

        // Take the unzip lines belonging to this target's section, in the order printed.
        List<String> archives = new ArrayList<>();
        boolean inSection = false;
        Pattern unzip = Pattern.compile("unzip -o '<backup folder>/(.+)'");
        for (String line : readme.split("\n")) {
            if (line.startsWith("--- ")) inSection = line.startsWith("--- " + targetId);
            if (!inSection) continue;
            Matcher m = unzip.matcher(line);
            if (m.find()) archives.add(m.group(1));
        }
        assertFalse(archives.isEmpty(), "RESTORE.txt named no archives for " + targetId);

        Files.createDirectories(into);
        for (String rel : archives) {
            Path zipPath = storeRoot.resolve(rel);
            assertTrue(Files.isRegularFile(zipPath), "RESTORE.txt names a missing archive: " + rel);
            try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zipPath))) {
                ZipEntry e;
                while ((e = zin.getNextEntry()) != null) {
                    Path dst = into.resolve(e.getName());
                    Files.createDirectories(dst.getParent());
                    Files.copy(zin, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    @Test
    @DisplayName("a backup chain restores byte-for-byte with nothing but unzip")
    void chainRestoresByHand(@TempDir Path tmp) throws Exception {
        Path ext = tmp.resolve("device");
        Path store = tmp.resolve("store");
        LocalFolderSink sink = new LocalFolderSink(store.toString());

        // Ten games, so that changing one is a small fraction of the target. With a handful of
        // files an incremental would be pointless and ArchivePolicy would correctly rebase to a
        // full instead, which is not what this test is exercising.
        for (int i = 1; i <= 10; i++) write(ext.resolve("saves/game" + i + "/save.dat"), "v1-" + i);
        write(ext.resolve("cards/card1.bin"), "card-one");

        BackupRunner.Result r1 = runner(ext, sink).run(null, "manual", 1_700_000_000_000L,
                BackupRunner.SILENT);
        assertFalse(r1.cancelled);
        assertEquals("full", r1.manifest.target("saves").mode);

        // Change one file, add one, delete one, and touch another without changing its content.
        write(ext.resolve("saves/game1/save.dat"), "alpha-v2");
        write(ext.resolve("saves/game11/save.dat"), "gamma");
        Files.delete(ext.resolve("saves/game2/save.dat"));
        ext.resolve("cards/card1.bin").toFile().setLastModified(1_700_000_500_000L);

        BackupRunner.Result r2 = runner(ext, sink).run(null, "manual", 1_700_000_600_000L,
                BackupRunner.SILENT);
        assertEquals("incremental", r2.manifest.target("saves").mode);
        assertEquals("unchanged", r2.manifest.target("cards").mode,
                "a touched but identical file must not produce a new archive");

        write(ext.resolve("saves/game11/save.dat"), "gamma-v2");
        BackupRunner.Result r3 = runner(ext, sink).run(null, "manual", 1_700_001_200_000L,
                BackupRunner.SILENT);

        // --- restore by hand, from the document, with no app code ---
        Path rebuilt = tmp.resolve("rebuilt");
        handRestore(store, r3.versionId, "saves", rebuilt);

        Map<String, String> live = snapshot(ext.resolve("saves"));
        Map<String, String> restored = snapshot(rebuilt);

        // game2 was deleted after the base archive; a hand restore leaves it behind, which
        // RESTORE.txt states explicitly. Everything else must match exactly.
        assertEquals("alpha-v2", restored.get("game1/save.dat"));
        assertEquals("gamma-v2", restored.get("game11/save.dat"));
        assertEquals("v1-2", restored.get("game2/save.dat"),
                "the stale file should still be there, as the instructions warn");

        restored.remove("game2/save.dat");
        assertEquals(live, restored, "hand restore did not reproduce the live tree");
    }

    @Test
    @DisplayName("an unchanged target adds no archive to a later version")
    void unchangedTargetsCostNothing(@TempDir Path tmp) throws Exception {
        Path ext = tmp.resolve("device");
        LocalFolderSink sink = new LocalFolderSink(tmp.resolve("store").toString());
        for (int i = 1; i <= 6; i++) write(ext.resolve("saves/a" + i + ".dat"), "x" + i);
        write(ext.resolve("cards/c.bin"), "y");

        runner(ext, sink).run(null, "manual", 1_000L, BackupRunner.SILENT);
        write(ext.resolve("saves/a1.dat"), "x1-changed");
        BackupRunner.Result r2 = runner(ext, sink).run(null, "manual", 2_000L, BackupRunner.SILENT);

        List<String> files = sink.listFiles(r2.versionId);
        assertTrue(files.contains("saves.inc.zip"));
        assertFalse(files.toString().contains("cards"),
                "the untouched target must not appear in this version at all: " + files);
        // Its chain still points back at the version that does hold its bytes.
        assertEquals(Arrays.asList(r2.manifest.target("cards").chain.get(0)),
                r2.manifest.target("cards").chain);
    }

    @Test
    @DisplayName("SHA256SUMS is in the exact format sha256sum -c accepts")
    void sha256sumsFormat(@TempDir Path tmp) throws Exception {
        Path ext = tmp.resolve("device");
        LocalFolderSink sink = new LocalFolderSink(tmp.resolve("store").toString());
        write(ext.resolve("saves/a.dat"), "payload");
        BackupRunner.Result r = runner(ext, sink).run(null, "manual", 1_000L, BackupRunner.SILENT);

        String sums = new String(Files.readAllBytes(
                tmp.resolve("store").resolve(r.versionId).resolve("SHA256SUMS")), StandardCharsets.UTF_8);
        assertFalse(sums.isEmpty());
        for (String line : sums.split("\n")) {
            if (line.isEmpty()) continue;
            assertTrue(line.matches("^[0-9a-f]{64} {2}\\S.*$"),
                    "not sha256sum -c format (needs exactly two spaces): [" + line + "]");
        }

        // And the recorded hashes must actually match the archives on disk.
        for (ManifestTarget t : r.manifest.targets) {
            if (t.archive == null) continue;
            Path zip = tmp.resolve("store").resolve(r.versionId).resolve(t.archive);
            try (InputStream in = Files.newInputStream(zip)) {
                assertEquals(t.archiveSha256, Hashes.sha256(in), "checksum mismatch for " + t.archive);
            }
        }
    }

    @Test
    @DisplayName("a version with no new archives does not tell you to run a check that would fail")
    void unchangedVersionReadmeIsHonest(@TempDir Path tmp) throws Exception {
        Path ext = tmp.resolve("device");
        Path store = tmp.resolve("store");
        LocalFolderSink sink = new LocalFolderSink(store.toString());
        write(ext.resolve("saves/a.dat"), "one");
        runner(ext, sink).run(null, "manual", 1_000L, BackupRunner.SILENT);

        // Nothing touched, so this version writes no archives and SHA256SUMS is empty.
        BackupRunner.Result r2 = runner(ext, sink).run(null, "manual", 2_000L, BackupRunner.SILENT);

        String sums = new String(Files.readAllBytes(
                store.resolve(r2.versionId).resolve("SHA256SUMS")), StandardCharsets.UTF_8);
        assertTrue(sums.isEmpty(), "precondition: no archives means no checksums");

        String readme = new String(Files.readAllBytes(
                store.resolve(r2.versionId).resolve("RESTORE.txt")), StandardCharsets.UTF_8);
        assertFalse(readme.contains("sha256sum -c"),
                "the readme offers a check that would fail on an empty SHA256SUMS");
        assertTrue(readme.contains("Nothing changed since the previous backup"));
        // It must still tell the reader how to get their data back.
        assertTrue(readme.contains("unzip -o"), "the readme must still name the archives to extract");
    }

    @Test
    @DisplayName("the index survives being deleted, because the manifests are the real record")
    void indexIsRebuildable(@TempDir Path tmp) throws Exception {
        Path ext = tmp.resolve("device");
        Path store = tmp.resolve("store");
        LocalFolderSink sink = new LocalFolderSink(store.toString());
        write(ext.resolve("saves/a.dat"), "one");
        runner(ext, sink).run(null, "manual", 1_000L, BackupRunner.SILENT);
        write(ext.resolve("saves/a.dat"), "two");
        runner(ext, sink).run(null, "manual", 2_000L, BackupRunner.SILENT);

        Files.delete(store.resolve("index.json"));

        write(ext.resolve("saves/a.dat"), "three");
        BackupRunner.Result r3 = runner(ext, sink).run(null, "manual", 3_000L, BackupRunner.SILENT);

        // Numbering continues rather than restarting at v0001 and overwriting history.
        assertEquals(3, VersionId.parse(r3.versionId).counter);
        assertTrue(Files.isRegularFile(store.resolve("index.json")));
    }

    @Test
    @DisplayName("coupled targets are pulled in even when the caller forgets them")
    void coupledTargetsAreForced(@TempDir Path tmp) throws Exception {
        String reg = "{\"registryVersion\":1,\"emulators\":[{"
                + "\"id\":\"emu\",\"label\":\"Emu\",\"packages\":[\"a.b.c\"],\"targets\":["
                + "{\"id\":\"saves\",\"label\":\"S\",\"category\":\"SAVE\",\"tier\":\"SHARED\","
                + "\"root\":\"{EXT}/saves\",\"include\":[\"**\"],\"maxBytes\":1048576,"
                + "\"coupledWith\":[\"profiles\"]},"
                + "{\"id\":\"profiles\",\"label\":\"P\",\"category\":\"SAVE\",\"tier\":\"SHARED\","
                + "\"root\":\"{EXT}/profiles\",\"include\":[\"**\"],\"maxBytes\":1048576}"
                + "]}]}";
        Path ext = tmp.resolve("device");
        LocalFolderSink sink = new LocalFolderSink(tmp.resolve("store").toString());
        write(ext.resolve("saves/a.dat"), "save");
        write(ext.resolve("profiles/profiles.dat"), "profile");

        TargetRegistry registry = TargetRegistry.parse(reg);
        PathResolver res = new PathResolver(ext.toString());
        FileSource src = new LocalFileSource();
        Capabilities caps = new Capabilities(true, false, false);
        BackupRunner run = new BackupRunner(registry,
                new ScanEngine(res, src, null, caps, PackagePresence.ALL_PRESENT),
                res, src, null, sink, caps, EmulatorVersions.UNKNOWN, PackagePresence.ALL_PRESENT);

        // Asking only for the saves must still capture the profile they are keyed to. Restoring
        // Switch saves without profiles.dat leaves them orphaned — the original incident.
        BackupRunner.Result r = run.run(Arrays.asList("saves"), "manual", 1_000L, BackupRunner.SILENT);
        assertNotNull(r.manifest.target("profiles"), "the coupled target was not backed up");
        assertEquals(1, r.manifest.target("profiles").files.size());
    }
}
