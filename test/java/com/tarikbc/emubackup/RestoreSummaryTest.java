package com.tarikbc.emubackup;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The words a person reads before a restore writes anything. */
class RestoreSummaryTest {

    private static final class Hasher implements RestorePlanner.Hasher {
        final java.util.Map<String, String> h = new java.util.HashMap<>();
        Hasher put(String p, String v) { h.put(p, v); return this; }
        @Override public String sha256(String p) { return h.getOrDefault(p, "??"); }
    }

    private static ManifestTarget backup(ManifestFile... files) {
        return new ManifestTarget("eden-saves", "eden", Tier.SHARED, Category.SAVE, "/root",
                TargetStatus.OK, "full", null, "t.full.zip", "deadbeef", 100, 100, 0,
                Arrays.asList("v0001/t.full.zip"), null, -1, Arrays.asList(files),
                new ArrayList<>(), new ArrayList<>(), null);
    }

    private static ManifestFile mf(String p, long size, long mtime, String hash) {
        return new ManifestFile(p, size, mtime, hash, "v0001");
    }

    private static FileStat fs(String p, long size, long mtime) { return new FileStat(p, size, mtime); }

    private static RestoreSummary describe(RestorePlan plan) {
        return RestoreSummary.describe(plan, "Mario Kart 8 Deluxe", "Tarik", "Wed 16 Sep, 12:00 PM",
                "Eden", 10_000L);
    }

    @Test
    @DisplayName("a missing file is put back and nothing on the device is replaced")
    void createOnly() throws Exception {
        Hasher h = new Hasher().put("mk8/sg00.dat", "bb");
        RestorePlan p = RestorePlanner.plan(backup(mf("mk8/userdata.dat", 79_472, 100, "aa"),
                mf("mk8/sg00.dat", 10, 100, "bb")), "Game saves",
                Arrays.asList(fs("mk8/sg00.dat", 10, 100)), null, h, true);
        RestoreSummary s = describe(p);

        assertFalse(s.alreadyThere);
        assertEquals("Put back Mario Kart 8 Deluxe (Tarik) from Wed 16 Sep, 12:00 PM?", s.title);
        assertTrue(s.body.contains("Puts back 1 missing file"), s.body);
        assertTrue(s.body.contains("userdata.dat"), s.body);
        assertTrue(s.body.contains("1 file already matches"), s.body);
        assertTrue(s.body.contains("Nothing on the device is replaced."), s.body);
        assertTrue(s.body.contains("Eden may need relaunching"), s.body);
        assertEquals("Put it back", s.primary);
        assertEquals("Cancel", s.secondary);
        assertNull(s.secondaryPlan);
        assertEquals(1, s.primaryPlan.toWrite().size());
    }

    @Test
    @DisplayName("newer files on the device give a real choice: keep them or replace them")
    void newerFilesAreAChoice() throws Exception {
        Hasher h = new Hasher().put("mk8/a.dat", "zz");
        RestorePlan p = RestorePlanner.plan(backup(mf("mk8/a.dat", 10, 100, "aa"),
                mf("mk8/b.dat", 20, 100, "bb")), "Game saves",
                Arrays.asList(fs("mk8/a.dat", 10, 9_999)), null, h, true);
        RestoreSummary s = describe(p);

        assertTrue(s.body.contains("1 file on this device is newer"), s.body);
        assertEquals("Keep newer files", s.secondary);
        assertEquals("Replace them anyway", s.primary);
        assertEquals(1, s.secondaryPlan.toWrite().size(), "keeping newer files still puts back the missing one");
        assertEquals(2, s.primaryPlan.toWrite().size());
        assertTrue(s.body.contains("safety copy"), s.body);
    }

    @Test
    @DisplayName("when only newer files differ the choice is replace or cancel")
    void newerOnly() throws Exception {
        Hasher h = new Hasher().put("mk8/a.dat", "zz");
        RestorePlan p = RestorePlanner.plan(backup(mf("mk8/a.dat", 10, 100, "aa")), "Game saves",
                Arrays.asList(fs("mk8/a.dat", 10, 9_999)), null, h, true);
        RestoreSummary s = describe(p);

        assertEquals("Cancel", s.secondary);
        assertNull(s.secondaryPlan);
        assertEquals("Replace it anyway", s.primary);
        assertEquals(1, s.primaryPlan.toWrite().size());
    }

    @Test
    @DisplayName("a save that already matches has nothing to put back")
    void alreadyThere() throws Exception {
        Hasher h = new Hasher().put("mk8/a.dat", "aa");
        RestorePlan p = RestorePlanner.plan(backup(mf("mk8/a.dat", 10, 100, "aa")), "Game saves",
                Arrays.asList(fs("mk8/a.dat", 10, 100)), null, h, true);
        assertTrue(describe(p).alreadyThere);
    }

    @Test
    @DisplayName("more than three files are counted, not named")
    void manyFilesAreCounted() throws Exception {
        RestorePlan p = RestorePlanner.plan(backup(mf("mk8/a.dat", 1, 100, "a"), mf("mk8/b.dat", 1, 100, "b"),
                mf("mk8/c.dat", 1, 100, "c"), mf("mk8/d.dat", 1, 100, "d")), "Game saves",
                new ArrayList<>(), null, new Hasher(), true);
        RestoreSummary s = describe(p);
        assertTrue(s.body.contains("Puts back 4 missing files"), s.body);
        assertFalse(s.body.contains("a.dat"), s.body);
    }

    @Test
    @DisplayName("older files are replaced with a safety copy, and files added since are left alone")
    void replacedAndOrphans() throws Exception {
        Hasher h = new Hasher().put("mk8/a.dat", "zz");
        RestorePlan p = RestorePlanner.plan(backup(mf("mk8/a.dat", 10, 100, "aa")), "Game saves",
                Arrays.asList(fs("mk8/a.dat", 10, 50), fs("mk8/replay.dat", 5, 50)), null, h, true);
        RestoreSummary s = describe(p);
        assertTrue(s.body.contains("Replaces 1 older file"), s.body);
        assertTrue(s.body.contains("a.dat"), s.body);
        assertTrue(s.body.contains("safety copy"), s.body);
        assertTrue(s.body.contains("1 file added since then is left in place"), s.body);
        assertFalse(s.body.contains("Nothing on the device is replaced"), s.body);
        assertEquals("Put it back", s.primary);
    }

    @Test
    @DisplayName("the title has no profile when the game has none")
    void noProfile() throws Exception {
        RestorePlan p = RestorePlanner.plan(backup(mf("a.dat", 1, 100, "a")), "Saves",
                new ArrayList<>(), null, new Hasher(), true);
        RestoreSummary s = RestoreSummary.describe(p, "Zelda", null, "Today, 9:00 AM", "Dolphin", 0L);
        assertEquals("Put back Zelda from Today, 9:00 AM?", s.title);
    }

    @Test
    @DisplayName("a safety copy holds only what a restore replaced, so the rest is simply not touched")
    void safetyCopyWording() throws Exception {
        Hasher h = new Hasher().put("mk8/a.dat", "zz");
        RestorePlan p = RestorePlanner.plan(backup(mf("mk8/a.dat", 10, 100, "aa")), "Game saves",
                Arrays.asList(fs("mk8/a.dat", 10, 50), fs("mk8/replay.dat", 5, 50)), null, h, true);
        RestoreSummary s = RestoreSummary.describe(p, "Mario Kart 8 Deluxe", "Tarik",
                "Wed 16 Sep, 12:00 PM", "Eden", 10_000L, true);
        assertEquals("Put back Mario Kart 8 Deluxe (Tarik) from the safety copy of Wed 16 Sep, 12:00 PM?",
                s.title);
        assertTrue(s.body.contains("Replaces 1 older file"), s.body);
        assertTrue(s.body.contains("The other 1 file is not in the safety copy and is not touched."), s.body);
        assertFalse(s.body.contains("added since then"), s.body);
    }
}
