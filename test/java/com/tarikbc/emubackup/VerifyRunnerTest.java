package com.tarikbc.emubackup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verification is only worth anything if it fails when it should, so every test here breaks a
 * store in a specific way and asserts that the damage is noticed and named.
 */
class VerifyRunnerTest {

    private static void write(Path p, String body) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, body);
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

    private static final String REGISTRY =
            "{\"registryVersion\":1,\"emulators\":[{"
            + "\"id\":\"emu\",\"label\":\"Emu\",\"packages\":[\"a.b.c\"],\"targets\":["
            + "{\"id\":\"saves\",\"label\":\"Saves\",\"category\":\"SAVE\",\"tier\":\"SHARED\","
            + "\"root\":\"{EXT}/saves\",\"include\":[\"**\"],\"maxBytes\":104857600},"
            + "{\"id\":\"cards\",\"label\":\"Cards\",\"category\":\"SAVE\",\"tier\":\"SHARED\","
            + "\"root\":\"{EXT}/cards\",\"include\":[\"**\"],\"maxBytes\":104857600}"
            + "]}]}";

    /** Two versions: v1 holds both targets, v2 is an incremental that still needs v1's cards. */
    private static String[] twoVersions(Path ext, LocalFolderSink sink) throws Exception {
        write(ext.resolve("saves/a.dat"), "one");
        write(ext.resolve("cards/c.bin"), "card");
        String v1 = runner(ext, sink).run(null, "manual", 1_000L, BackupRunner.SILENT).versionId;
        write(ext.resolve("saves/a.dat"), "two");
        String v2 = runner(ext, sink).run(null, "manual", 2_000L, BackupRunner.SILENT).versionId;
        return new String[] { v1, v2 };
    }

    @Test
    @DisplayName("a sound version passes, and says how much it actually read")
    void soundVersionPasses(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        LocalFolderSink sink = new LocalFolderSink(store.toString());
        String[] v = twoVersions(tmp.resolve("device"), sink);

        VerifyRunner.Result r = VerifyRunner.verify(sink, v[1], null);
        assertTrue(r.ok(), r.problems.toString());
        assertEquals(r.archivesExpected, r.archivesChecked);
        assertTrue(r.bytesRead > 0, "claimed to verify without reading anything");
        assertTrue(r.summary().contains("matches"), r.summary());
    }

    @Test
    @DisplayName("verifying a version checks the older archives its chain still needs")
    void checksTheWholeChain(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        LocalFolderSink sink = new LocalFolderSink(store.toString());
        String[] v = twoVersions(tmp.resolve("device"), sink);

        // v2 wrote only saves.inc.zip, so anything beyond one archive comes from v1.
        VerifyRunner.Result r = VerifyRunner.verify(sink, v[1], null);
        assertTrue(r.archivesExpected >= 2,
                "only checked this version's own archives: " + r.archivesExpected);
    }

    @Test
    @DisplayName("a truncated archive is caught, and named")
    void truncationIsCaught(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        LocalFolderSink sink = new LocalFolderSink(store.toString());
        String[] v = twoVersions(tmp.resolve("device"), sink);

        Path victim = store.resolve(v[0]).resolve("cards.full.zip");
        byte[] whole = Files.readAllBytes(victim);
        Files.write(victim, Arrays.copyOf(whole, whole.length - 10));

        VerifyRunner.Result r = VerifyRunner.verify(sink, v[1], null);
        assertFalse(r.ok());
        assertTrue(r.problems.toString().contains("cards.full.zip"), r.problems.toString());
    }

    @Test
    @DisplayName("a silently substituted archive is caught by the checksum, not by its size")
    void substitutionIsCaught(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        LocalFolderSink sink = new LocalFolderSink(store.toString());
        String[] v = twoVersions(tmp.resolve("device"), sink);

        Path victim = store.resolve(v[0]).resolve("cards.full.zip");
        byte[] whole = Files.readAllBytes(victim);
        // Same length, different bytes: a size check would see nothing wrong.
        byte[] tampered = whole.clone();
        tampered[tampered.length / 2] ^= 0xFF;
        Files.write(victim, tampered);

        VerifyRunner.Result r = VerifyRunner.verify(sink, v[1], null);
        assertFalse(r.ok());
        assertTrue(r.problems.toString().contains("checksum"), r.problems.toString());
    }

    @Test
    @DisplayName("a missing archive in an older version breaks the newer one, and is reported so")
    void brokenChainIsCaught(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        LocalFolderSink sink = new LocalFolderSink(store.toString());
        String[] v = twoVersions(tmp.resolve("device"), sink);

        Files.delete(store.resolve(v[0]).resolve("cards.full.zip"));

        VerifyRunner.Result r = VerifyRunner.verify(sink, v[1], null);
        assertFalse(r.ok(), "a deleted base archive passed verification");
        assertTrue(r.problems.toString().contains("missing"), r.problems.toString());
    }

    @Test
    @DisplayName("stopping mid-way claims nothing")
    void cancellationProvesNothing(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        LocalFolderSink sink = new LocalFolderSink(store.toString());
        String[] v = twoVersions(tmp.resolve("device"), sink);

        VerifyRunner.Result r = VerifyRunner.verify(sink, v[1], new VerifyRunner.Listener() {
            @Override public void onProgress(String a, int d, int t) { }
            @Override public boolean isCancelled() { return true; }
        });
        assertFalse(r.ok());
        assertTrue(r.cancelled);
        assertTrue(r.summary().contains("Nothing is proven"), r.summary());
    }

    @Test
    @DisplayName("progress counts every archive that will be read, not just this version's")
    void progressCoversTheChain(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        LocalFolderSink sink = new LocalFolderSink(store.toString());
        String[] v = twoVersions(tmp.resolve("device"), sink);

        List<Integer> totals = new ArrayList<>();
        VerifyRunner.verify(sink, v[1], new VerifyRunner.Listener() {
            @Override public void onProgress(String a, int d, int t) { totals.add(t); }
            @Override public boolean isCancelled() { return false; }
        });
        assertFalse(totals.isEmpty(), "no progress was reported at all");
        for (int t : totals) assertEquals(totals.get(0).intValue(), t, "the total moved mid-run");
    }
}
