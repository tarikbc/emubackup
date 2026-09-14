package com.tarikbc.emubackup;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the <em>shipped</em> registry against a reconstruction of the real directory layout
 * observed on the reference device, recorded in {@code docs/PROVENANCE.md}.
 *
 * <p>{@code TargetRegistryTest} proves the registry is internally consistent. This proves it
 * matches reality: that the paths actually point where the saves are, and — the half that
 * matters more — that the ROMs and BIOS images sitting in the very same folders are not
 * picked up. A single wrong path segment here is the difference between a backup and an
 * empty folder, and neither the parser nor a size check would notice.
 */
class ShippedRegistryLayoutTest {

    private TargetRegistry reg;
    private Path ext;

    private static void file(Path p, int bytes) throws IOException {
        Files.createDirectories(p.getParent());
        byte[] b = new byte[bytes];
        Arrays.fill(b, (byte) 'x');
        Files.write(p, b);
    }

    @BeforeEach void setUp(@TempDir Path tmp) throws Exception {
        reg = TargetRegistry.parse(new String(
                Files.readAllBytes(Paths.get("src/main/assets/targets.json")),
                java.nio.charset.StandardCharsets.UTF_8));
        ext = tmp;

        // --- Switch: saves live inside the ROM tree, beside the ROMs themselves -----------
        String edenSave = "ROMs/switch/saves/user/save/0000000000000000/"
                + "F255133E7DABC494CD4B3089D53DB2DB/0100152000022000/";
        file(ext.resolve(edenSave + "save.dat"), 65536);
        file(ext.resolve(edenSave + "header.bin"), 512);
        file(ext.resolve("ROMs/switch/saves/user/save/0000000000000000/"
                + "85DB91DCBD304DA4C24810CF9BF88573/01007EF00011E000/save.dat"), 2048);
        file(ext.resolve("ROMs/switch/Custom Complete Fighters Savegame/save_data/x.bin"), 4096);
        file(ext.resolve("ROMs/switch/Super Mario Odyssey [0100000000010000][v0].nsp"), 3_000_000);
        file(ext.resolve("ROMs/switch/Updates and DLC/Some Update.nsp"), 2_000_000);

        // --- PS2 -------------------------------------------------------------------------
        file(ext.resolve("ROMs/ps2/memcards/Mcd001.ps2"), 8192);
        file(ext.resolve("ROMs/ps2/memcards/Mcd002.ps2"), 8192);
        file(ext.resolve("ROMs/ps2/memcard-backups/Mcd001.ps2.backup"), 8192);
        file(ext.resolve("ROMs/ps2/sstates/SLUS-20946 (1).00.p2s"), 13_000_000);
        file(ext.resolve("ROMs/ps2/God of War.iso"), 4_000_000);

        // --- PSP -------------------------------------------------------------------------
        file(ext.resolve("ROMs/psp/PSP/SAVEDATA/ULUS10336/DATA.BIN"), 4096);
        file(ext.resolve("ROMs/psp/PSP/PPSSPP_STATE/ULUS10336_1.00_0.ppst"), 5_000_000);
        file(ext.resolve("ROMs/psp/Patapon.iso"), 1_500_000);

        // --- 3DS, Azahar ------------------------------------------------------------------
        file(ext.resolve("ROMs/n3ds/nand/data/00000000/sysdata/00010017/00000000"), 1024);
        file(ext.resolve("ROMs/n3ds/sdmc/Nintendo 3DS/00000000000000000000000000000000/"
                + "11111111111111111111111111111111/title/00040000/001a0500/data/00000001.sav"), 8192);
        file(ext.resolve("ROMs/n3ds/states/001a0500.state"), 20_000_000);

        // --- DS: saves interleaved with multi-GB ROM archives -----------------------------
        file(ext.resolve("ROMs/nds/Pokemon Platinum.sav"), 524288);
        file(ext.resolve("ROMs/nds/Pokemon Platinum.zip"), 4_000_000);
        file(ext.resolve("ROMs/nds/Mario Kart DS.nds"), 3_000_000);

        // --- RetroArch: system/ holds BIOS and the tiny VMU saves together -----------------
        file(ext.resolve("RetroArch/saves/Zelda.srm"), 32768);
        file(ext.resolve("RetroArch/states/Zelda.state1"), 1_000_000);
        file(ext.resolve("RetroArch/system/vmu_save_A1.bin"), 131072);
        file(ext.resolve("RetroArch/system/dc_boot.bin"), 2_000_000);
        file(ext.resolve("RetroArch/system/scph5500.bin"), 524288);
        file(ext.resolve("RetroArch/system/dc/naomi.zip"), 1_000_000);
    }

    private Map<String, TargetScan> scanAll(Capabilities caps) {
        ScanEngine engine = new ScanEngine(new PathResolver(ext.toString()),
                new LocalFileSource(), new LocalFileSource(), caps, PackagePresence.ALL_PRESENT);
        Map<String, TargetScan> out = new HashMap<>();
        for (Emulator e : reg.emulators()) {
            for (TargetScan s : engine.scanAll(e, e.targets)) out.put(s.targetId, s);
        }
        return out;
    }

    private static Set<String> paths(TargetScan s) {
        Set<String> out = new HashSet<>();
        for (FileStat f : s.files) out.add(f.path);
        return out;
    }

    @Test
    @DisplayName("Eden's saves are found, and the .nsp ROMs beside them are not")
    void edenSaves() {
        TargetScan s = scanAll(new Capabilities(true, false, false)).get("eden-saves");
        assertEquals(TargetStatus.OK, s.status);
        assertEquals(3, s.fileCount());
        assertEquals(68096, s.totalBytes);
        for (String p : paths(s)) {
            assertFalse(p.endsWith(".nsp"), "a ROM leaked into the Eden save target: " + p);
        }
    }

    @Test
    @DisplayName("Eden save paths decompose into profile and game")
    void edenGroupingMatchesTheRealTree() {
        TargetScan s = scanAll(new Capabilities(true, false, false)).get("eden-saves");
        Grouping g = reg.target("eden-saves").grouping;
        assertNotNull(g.pattern);

        Set<String> games = new HashSet<>(), profiles = new HashSet<>();
        for (FileStat f : s.files) {
            Map<String, String> m = g.pattern.match(f.path);
            assertNotNull(m, "grouping rule did not match a real save path: " + f.path);
            games.add(m.get("game"));
            profiles.add(m.get("profile"));
        }
        assertEquals(new HashSet<>(Arrays.asList("0100152000022000", "01007EF00011E000")), games);
        assertEquals(new HashSet<>(Arrays.asList(
                "F255133E7DABC494CD4B3089D53DB2DB", "85DB91DCBD304DA4C24810CF9BF88573")), profiles);
    }

    @Test
    @DisplayName("the manual Eden save drop outside saves/ is picked up by its own target")
    void edenCustomDrop() {
        TargetScan s = scanAll(new Capabilities(true, false, false)).get("eden-custom-drop");
        assertEquals(TargetStatus.OK, s.status);
        assertEquals(1, s.fileCount());
    }

    @Test
    @DisplayName("the RetroArch VMU save is found and 4 GB of BIOS beside it is not")
    void retroarchVmuAgainstBios() {
        TargetScan s = scanAll(new Capabilities(true, false, false)).get("retroarch-vmu");
        assertEquals(TargetStatus.OK, s.status);
        assertEquals(java.util.Collections.singleton("vmu_save_A1.bin"), paths(s));
        assertEquals(131072, s.totalBytes);
        assertTrue(s.unmatchedCount >= 2, "the BIOS files should be counted as ignored");
    }

    @Test
    @DisplayName("the DS save is found and the ROM archive beside it is not")
    void melondsAgainstRoms() {
        TargetScan s = scanAll(new Capabilities(true, false, false)).get("melonds-saves");
        assertEquals(TargetStatus.OK, s.status);
        assertEquals(java.util.Collections.singleton("Pokemon Platinum.sav"), paths(s));
    }

    @Test
    @DisplayName("PS2 memory cards are found and the .iso beside them is not")
    void ps2Memcards() {
        Map<String, TargetScan> all = scanAll(new Capabilities(true, false, false));
        TargetScan s = all.get("ps2-memcards");
        assertEquals(TargetStatus.OK, s.status);
        assertEquals(2, s.fileCount());
        for (String p : paths(s)) assertFalse(p.endsWith(".iso"));
    }

    @Test
    @DisplayName("PSP saves group by game id")
    void pspSaves() {
        TargetScan s = scanAll(new Capabilities(true, false, false)).get("ppsspp-saves");
        assertEquals(TargetStatus.OK, s.status);
        Map<String, String> m = reg.target("ppsspp-saves").grouping.pattern.match(s.files.get(0).path);
        assertNotNull(m);
        assertEquals("ULUS10336", m.get("game"));
    }

    @Test
    @DisplayName("the default selection excludes every save state on the device")
    void defaultSelectionExcludesStates() {
        Map<String, TargetScan> all = scanAll(new Capabilities(true, false, false));
        long defaultBytes = 0, stateBytes = 0;
        for (Map.Entry<String, TargetScan> e : all.entrySet()) {
            if (!e.getValue().hasContent()) continue;
            Target t = reg.target(e.getKey());
            if (t.enabledByDefault) defaultBytes += e.getValue().totalBytes;
            if (t.category == Category.STATE) stateBytes += e.getValue().totalBytes;
        }
        // 13 MB PS2 + 5 MB PSP + 20 MB 3DS + 1 MB RetroArch of states exist in this layout.
        assertTrue(stateBytes > 38_000_000, "expected states to be present, got " + stateBytes);
        assertTrue(defaultBytes < 1_000_000,
                "save states leaked into the default selection: " + Sizes.human(defaultBytes));
    }

    @Test
    @DisplayName("without Shizuku every app-private target is locked, and no shared target is")
    void tierSplitIsClean() {
        Map<String, TargetScan> all = scanAll(new Capabilities(true, false, false));
        int locked = 0;
        for (Map.Entry<String, TargetScan> e : all.entrySet()) {
            Target t = reg.target(e.getKey());
            if (t.tier == Tier.APP_PRIVATE) {
                assertEquals(TargetStatus.TIER_UNAVAILABLE, e.getValue().status,
                        t.id + " should be locked without Shizuku");
                locked++;
            } else {
                assertNotEquals(TargetStatus.TIER_UNAVAILABLE, e.getValue().status,
                        t.id + " is shared storage and must not be locked");
            }
        }
        assertEquals(19, locked, "expected the 19 app-private targets from the inventory");
    }

    @Test
    @DisplayName("no target reports a problem against a healthy device layout")
    void noSpuriousProblems() {
        for (TargetScan s : scanAll(new Capabilities(true, false, false)).values()) {
            assertFalse(s.status.isProblem(), s.targetId + " reported " + s.status + ": " + s.detail);
        }
    }

    @Test
    @DisplayName("nothing in the ROM tree is reachable by any target")
    void noRomIsEverMatched() {
        // The single most important property of the whole registry.
        List<String> romMarkers = Arrays.asList(".nsp", ".iso", ".zip", ".nds", "dc_boot.bin", "scph5500.bin");
        for (TargetScan s : scanAll(new Capabilities(true, true, false)).values()) {
            for (FileStat f : s.files) {
                for (String marker : romMarkers) {
                    assertFalse(f.path.endsWith(marker),
                            "target " + s.targetId + " matched a ROM or BIOS file: " + f.path);
                }
            }
        }
    }
}
