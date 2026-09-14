package com.tarikbc.emubackup;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScanEngineTest {

    private static final Capabilities ALL = new Capabilities(true, true, false);
    private static final Capabilities SHARED_ONLY = new Capabilities(true, false, false);

    private static void write(Path p, int bytes) throws IOException {
        Files.createDirectories(p.getParent());
        byte[] b = new byte[bytes];
        Arrays.fill(b, (byte) 'x');
        Files.write(p, b);
    }

    /** A registry holding one target, so tests exercise the real parser rather than a mock. */
    private static TargetRegistry reg(String targetBody) {
        return TargetRegistry.parse("{\"registryVersion\":1,\"emulators\":[{"
                + "\"id\":\"e\",\"label\":\"E\",\"packages\":[\"a.b.c\"],\"targets\":["
                + targetBody + "]}]}");
    }

    private static String shared(String id, String rootSuffix, String include, String extra) {
        return "{\"id\":\"" + id + "\",\"label\":\"L\",\"category\":\"SAVE\",\"tier\":\"SHARED\","
                + "\"root\":\"{EXT}" + rootSuffix + "\",\"include\":" + include
                + ",\"maxBytes\":1048576" + extra + "}";
    }

    private ScanEngine engine(Path ext, Capabilities caps, PackagePresence presence) {
        return new ScanEngine(new PathResolver(ext.toString()), new LocalFileSource(),
                new LocalFileSource(), caps, presence);
    }

    private static Set<String> paths(TargetScan s) {
        Set<String> out = new HashSet<>();
        for (FileStat f : s.files) out.add(f.path);
        return out;
    }

    @Test
    @DisplayName("a matching target reports OK with only the matching files")
    void scansAndFilters(@TempDir Path ext) throws Exception {
        write(ext.resolve("saves/a.sav"), 100);
        write(ext.resolve("saves/b.sav"), 200);
        write(ext.resolve("saves/rom.zip"), 5_000_000);

        TargetRegistry r = reg(shared("t", "/saves", "[\"**/*.sav\"]", ""));
        TargetScan s = engine(ext, ALL, PackagePresence.ALL_PRESENT).scan(r.emulator("e"), r.target("t"));

        assertEquals(TargetStatus.OK, s.status);
        assertEquals(new HashSet<>(Arrays.asList("a.sav", "b.sav")), paths(s));
        assertEquals(300, s.totalBytes);
        assertEquals(1, s.unmatchedCount, "the ROM should be counted as not matching");
        assertTrue(s.unmatchedSample.contains("rom.zip"));
    }

    @Test
    @DisplayName("a root that exists but holds nothing matching is EMPTY, not a failure")
    void emptyIsNotAFailure(@TempDir Path ext) throws Exception {
        write(ext.resolve("saves/rom.zip"), 10);
        TargetRegistry r = reg(shared("t", "/saves", "[\"**/*.sav\"]", ""));
        TargetScan s = engine(ext, ALL, PackagePresence.ALL_PRESENT).scan(r.emulator("e"), r.target("t"));
        assertEquals(TargetStatus.EMPTY, s.status);
        assertFalse(s.status.isProblem());
        assertEquals(1, s.unmatchedCount);
    }

    @Test
    @DisplayName("an absent root is ROOT_MISSING, which is normal before first launch")
    void missingRoot(@TempDir Path ext) {
        TargetRegistry r = reg(shared("t", "/never-created", "[\"**\"]", ""));
        TargetScan s = engine(ext, ALL, PackagePresence.ALL_PRESENT).scan(r.emulator("e"), r.target("t"));
        assertEquals(TargetStatus.ROOT_MISSING, s.status);
        assertFalse(s.status.isProblem());
        assertNotNull(s.resolvedRoot);
    }

    @Test
    @DisplayName("exceeding maxBytes refuses the target instead of truncating it")
    void overCapRefuses(@TempDir Path ext) throws Exception {
        write(ext.resolve("saves/big.sav"), 2_000_000); // cap is 1 MiB
        TargetRegistry r = reg(shared("t", "/saves", "[\"**\"]", ""));
        TargetScan s = engine(ext, ALL, PackagePresence.ALL_PRESENT).scan(r.emulator("e"), r.target("t"));

        assertEquals(TargetStatus.OVER_CAP, s.status);
        assertTrue(s.status.isProblem(), "over-cap must surface as a problem, not a quiet skip");
        assertTrue(s.files.isEmpty(), "nothing may be archived from an over-cap target");
        assertEquals(2_000_000, s.totalBytes, "the real size is still reported so the user can judge");
        assertNotNull(s.detail);
    }

    @Test
    @DisplayName("an app-private target without Shizuku is skipped, never failed")
    void appPrivateWithoutShizuku(@TempDir Path ext) throws Exception {
        write(ext.resolve("Android/data/a.b.c/files/x.sav"), 10);
        TargetRegistry r = reg("{\"id\":\"t\",\"label\":\"L\",\"category\":\"SAVE\",\"tier\":\"APP_PRIVATE\","
                + "\"pkg\":\"a.b.c\",\"root\":\"{DATA}/files\",\"include\":[\"**\"],\"maxBytes\":1048576}");

        TargetScan locked = engine(ext, SHARED_ONLY, PackagePresence.ALL_PRESENT)
                .scan(r.emulator("e"), r.target("t"));
        assertEquals(TargetStatus.TIER_UNAVAILABLE, locked.status);
        assertFalse(locked.status.isProblem(), "a locked tier is not an error");
        assertTrue(locked.detail.contains("Shizuku"));

        TargetScan unlocked = engine(ext, ALL, PackagePresence.ALL_PRESENT)
                .scan(r.emulator("e"), r.target("t"));
        assertEquals(TargetStatus.OK, unlocked.status);
        assertEquals(1, unlocked.fileCount());
    }

    @Test
    @DisplayName("a locked or absent target still reports the real path it would have read")
    void lockedTargetsStillCarryTheirPath(@TempDir Path ext) {
        // The UI shows this path to explain what Shizuku would unlock. A raw "{DATA}/..."
        // template there tells the user nothing and leaks an internal placeholder.
        TargetRegistry r = reg("{\"id\":\"t\",\"label\":\"L\",\"category\":\"SAVE\",\"tier\":\"APP_PRIVATE\","
                + "\"pkg\":\"a.b.c\",\"root\":\"{DATA}/files/nand\",\"include\":[\"**\"],\"maxBytes\":1048576}");

        TargetScan locked = engine(ext, SHARED_ONLY, PackagePresence.ALL_PRESENT)
                .scan(r.emulator("e"), r.target("t"));
        assertEquals(TargetStatus.TIER_UNAVAILABLE, locked.status);
        assertNotNull(locked.resolvedRoot);
        assertTrue(locked.resolvedRoot.endsWith("/Android/data/a.b.c/files/nand"), locked.resolvedRoot);
        assertFalse(locked.resolvedRoot.contains("{"), "the path must not contain a template variable");

        TargetScan absent = engine(ext, SHARED_ONLY, pkg -> false).scan(r.emulator("e"), r.target("t"));
        assertEquals(TargetStatus.PKG_NOT_INSTALLED, absent.status);
        assertNotNull(absent.resolvedRoot);
        assertFalse(absent.resolvedRoot.contains("{"));
    }

    @Test
    @DisplayName("a missing package is reported as such, even when Shizuku is absent too")
    void packageNotInstalledOutranksTierLock(@TempDir Path ext) {
        TargetRegistry r = reg("{\"id\":\"t\",\"label\":\"L\",\"category\":\"SAVE\",\"tier\":\"APP_PRIVATE\","
                + "\"pkg\":\"a.b.c\",\"root\":\"{DATA}/files\",\"include\":[\"**\"],\"maxBytes\":1048576}");
        TargetScan s = engine(ext, SHARED_ONLY, pkg -> false).scan(r.emulator("e"), r.target("t"));
        // "You have not installed this emulator" is more useful than "this needs Shizuku",
        // and it is knowable without any privileged access.
        assertEquals(TargetStatus.PKG_NOT_INSTALLED, s.status);
    }

    @Test
    @DisplayName("{DATA} resolves against whichever of the emulator's packages is installed")
    void resolvesAgainstTheInstalledPackage(@TempDir Path ext) throws Exception {
        // Eden ships as both a nightly and a stable package. The registry names one; the device
        // may have the other. Resolving against the registry's guess would read nothing.
        write(ext.resolve("Android/data/dev.eden.stable/files/nand/x.dat"), 42);
        TargetRegistry r = TargetRegistry.parse("{\"registryVersion\":1,\"emulators\":[{"
                + "\"id\":\"eden\",\"label\":\"Eden\",\"packages\":[\"dev.eden.nightly\",\"dev.eden.stable\"],"
                + "\"targets\":[{\"id\":\"t\",\"label\":\"L\",\"category\":\"SAVE\",\"tier\":\"APP_PRIVATE\","
                + "\"pkg\":\"dev.eden.nightly\",\"root\":\"{DATA}/files/nand\",\"include\":[\"**\"],"
                + "\"maxBytes\":1048576}]}]}");

        TargetScan s = engine(ext, ALL, pkg -> pkg.equals("dev.eden.stable"))
                .scan(r.emulator("eden"), r.target("t"));
        assertEquals(TargetStatus.OK, s.status);
        assertTrue(s.resolvedRoot.contains("dev.eden.stable"));
        assertEquals(42, s.totalBytes);
    }

    @Test
    @DisplayName("without all-files access, shared targets are locked rather than empty")
    void sharedTierNeedsAllFiles(@TempDir Path ext) throws Exception {
        write(ext.resolve("saves/a.sav"), 10);
        TargetRegistry r = reg(shared("t", "/saves", "[\"**\"]", ""));
        TargetScan s = engine(ext, Capabilities.none(), PackagePresence.ALL_PRESENT)
                .scan(r.emulator("e"), r.target("t"));
        assertEquals(TargetStatus.TIER_UNAVAILABLE, s.status);
        assertTrue(s.detail.contains("all-files"));
    }

    @Test
    @DisplayName("the RetroArch case: VMU saves are found and 4 GB of BIOS beside them is not")
    void retroarchVmuAgainstBios(@TempDir Path ext) throws Exception {
        write(ext.resolve("RetroArch/system/vmu_save_A1.bin"), 128);
        write(ext.resolve("RetroArch/system/dc_boot.bin"), 2_000_000);
        write(ext.resolve("RetroArch/system/dc/nested_vmu_save_x.bin"), 64);

        TargetRegistry r = reg(shared("vmu", "/RetroArch/system",
                "[\"vmu_save_*.bin\"]", ",\"recursive\":false"));
        TargetScan s = engine(ext, ALL, PackagePresence.ALL_PRESENT).scan(r.emulator("e"), r.target("vmu"));

        assertEquals(TargetStatus.OK, s.status);
        assertEquals(Collections.singleton("vmu_save_A1.bin"), paths(s));
        assertEquals(128, s.totalBytes);
    }

    @Test
    @DisplayName("an unreadable root is an error, not an empty result")
    void unreadableRootIsAnError(@TempDir Path ext) throws Exception {
        Path root = ext.resolve("saves");
        Files.createDirectories(root);
        write(root.resolve("a.sav"), 10);
        if (!root.toFile().setReadable(false, false)) return; // running as root; skip
        try {
            TargetRegistry r = reg(shared("t", "/saves", "[\"**\"]", ""));
            TargetScan s = engine(ext, ALL, PackagePresence.ALL_PRESENT).scan(r.emulator("e"), r.target("t"));
            // Reporting EMPTY here would tell the user they are covered when they are not.
            assertEquals(TargetStatus.UNREADABLE, s.status);
            assertTrue(s.status.isProblem());
        } finally {
            root.toFile().setReadable(true, false);
        }
    }

    @Test void aggregatesSummariseARun(@TempDir Path ext) throws Exception {
        write(ext.resolve("saves/a.sav"), 100);
        TargetRegistry r = TargetRegistry.parse("{\"registryVersion\":1,\"emulators\":[{"
                + "\"id\":\"e\",\"label\":\"E\",\"packages\":[\"a.b.c\"],\"targets\":["
                + shared("ok", "/saves", "[\"**\"]", "") + ","
                + shared("gone", "/nope", "[\"**\"]", "") + ","
                + "{\"id\":\"locked\",\"label\":\"L\",\"category\":\"SAVE\",\"tier\":\"APP_PRIVATE\","
                + "\"pkg\":\"a.b.c\",\"root\":\"{DATA}/f\",\"include\":[\"**\"],\"maxBytes\":1048576}"
                + "]}]}");
        List<TargetScan> all = engine(ext, SHARED_ONLY, PackagePresence.ALL_PRESENT)
                .scanAll(r.emulator("e"), r.allTargets());

        assertEquals(100, ScanEngine.totalBytes(all));
        assertEquals(1, ScanEngine.totalFiles(all));
        assertEquals(1, ScanEngine.lockedCount(all));
        assertTrue(ScanEngine.problems(all).isEmpty());
    }

    @Test
    @DisplayName("the unmatched sample is capped so a ROM folder cannot blow up memory")
    void unmatchedSampleIsCapped(@TempDir Path ext) throws Exception {
        for (int i = 0; i < 60; i++) write(ext.resolve("roms/game" + i + ".zip"), 10);
        write(ext.resolve("roms/a.sav"), 10);
        TargetRegistry r = reg(shared("t", "/roms", "[\"**/*.sav\"]", ""));
        TargetScan s = engine(ext, ALL, PackagePresence.ALL_PRESENT).scan(r.emulator("e"), r.target("t"));

        assertEquals(60, s.unmatchedCount, "the full count is still reported");
        assertEquals(TargetScan.UNMATCHED_SAMPLE_CAP, s.unmatchedSample.size());
    }
}
