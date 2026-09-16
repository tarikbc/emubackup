package com.tarikbc.emubackup;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RestorePlannerTest {

    private static final class CountingHasher implements RestorePlanner.Hasher {
        final java.util.Map<String, String> h = new java.util.HashMap<>();
        int calls;
        CountingHasher put(String p, String v) { h.put(p, v); return this; }
        @Override public String sha256(String p) { calls++; return h.getOrDefault(p, "??"); }
    }

    private static ManifestTarget backup(ManifestFile... files) {
        return new ManifestTarget("t", "emu", Tier.SHARED, Category.SAVE, "/root",
                TargetStatus.OK, "full", null, "t.full.zip", "deadbeef", 100, 100, 0,
                Arrays.asList("v0001/t.full.zip"), null, -1, Arrays.asList(files),
                new ArrayList<>(), new ArrayList<>(), null);
    }

    private static ManifestFile mf(String p, long size, long mtime, String hash) {
        return new ManifestFile(p, size, mtime, hash, "v0001");
    }

    private static FileStat fs(String p, long size, long mtime) { return new FileStat(p, size, mtime); }

    @Test
    @DisplayName("a file absent from the device is simply created")
    void create() throws Exception {
        RestorePlan p = RestorePlanner.plan(backup(mf("a.sav", 10, 100, "aa")), "T",
                new ArrayList<>(), null, new CountingHasher(), true);
        assertEquals(RestoreAction.CREATE, p.items.get(0).action);
        assertEquals(1, p.toWrite().size());
    }

    @Test
    @DisplayName("identical content is skipped")
    void skipIdentical() throws Exception {
        CountingHasher h = new CountingHasher().put("a.sav", "aa");
        RestorePlan p = RestorePlanner.plan(backup(mf("a.sav", 10, 100, "aa")), "T",
                Arrays.asList(fs("a.sav", 10, 100)), null, h, true);
        assertEquals(RestoreAction.SKIP_IDENTICAL, p.items.get(0).action);
        assertTrue(p.isNoOp());
    }

    @Test
    @DisplayName("a different size is not hashed, because the content already differs")
    void differentSizeSkipsTheHash() throws Exception {
        CountingHasher h = new CountingHasher();
        RestorePlan p = RestorePlanner.plan(backup(mf("a.sav", 10, 100, "aa")), "T",
                Arrays.asList(fs("a.sav", 999, 50)), null, h, true);
        assertEquals(0, h.calls, "a size mismatch already proves the content differs");
        assertEquals(RestoreAction.OVERWRITE_OLDER, p.items.get(0).action);
    }

    @Test
    @DisplayName("a file newer on the device is a conflict, and is skipped unless forced")
    void conflictNewerIsSkippedByDefault() throws Exception {
        // The failure this whole preview exists to prevent: restoring an old backup over
        // progress made since.
        CountingHasher h = new CountingHasher().put("a.sav", "zz");
        RestorePlan p = RestorePlanner.plan(backup(mf("a.sav", 10, 100, "aa")), "T",
                Arrays.asList(fs("a.sav", 10, 9_999)), null, h, true);

        assertEquals(RestoreAction.CONFLICT_NEWER, p.items.get(0).action);
        assertTrue(p.toWrite().isEmpty(), "a newer file must not be overwritten by default");
        assertEquals(9_999, p.newestConflictMtime());

        assertEquals(1, p.withForced(true).toWrite().size(), "forcing must apply it");
    }

    @Test
    @DisplayName("equal timestamps with different content is flagged, not quietly overwritten")
    void sizeConflict() throws Exception {
        CountingHasher h = new CountingHasher().put("a.sav", "zz");
        RestorePlan p = RestorePlanner.plan(backup(mf("a.sav", 10, 100, "aa")), "T",
                Arrays.asList(fs("a.sav", 10, 100)), null, h, true);
        assertEquals(RestoreAction.SIZE_CONFLICT, p.items.get(0).action);
        assertTrue(p.toWrite().isEmpty());
    }

    @Test
    @DisplayName("a file on the device but not in the backup is never deleted")
    void orphansAreLeftAlone() throws Exception {
        RestorePlan p = RestorePlanner.plan(backup(mf("a.sav", 10, 100, "aa")), "T",
                Arrays.asList(fs("a.sav", 10, 100), fs("extra.sav", 5, 5)), null,
                new CountingHasher().put("a.sav", "aa"), true);

        RestoreItem orphan = null;
        for (RestoreItem i : p.items) if (i.path.equals("extra.sav")) orphan = i;
        assertNotNull(orphan);
        assertEquals(RestoreAction.ORPHAN_ON_DEVICE, orphan.action);
        // Not in toWrite, and forcing does not delete it either.
        assertFalse(p.withForced(true).toWrite().contains(orphan));
    }

    @Test
    @DisplayName("an unwritable tier blocks every file rather than half-restoring")
    void blockedTier() throws Exception {
        RestorePlan p = RestorePlanner.plan(backup(mf("a.sav", 10, 100, "aa")), "T",
                new ArrayList<>(), null, new CountingHasher(), false);
        assertEquals(RestoreAction.BLOCKED_TIER, p.items.get(0).action);
        assertTrue(p.isNoOp());
        assertTrue(p.withForced(true).isNoOp(), "forcing must not bypass a tier that cannot be written");
    }

    @Test
    @DisplayName("a selection narrows the plan to the chosen files")
    void selection() throws Exception {
        RestorePlan p = RestorePlanner.plan(
                backup(mf("a.sav", 10, 100, "aa"), mf("b.sav", 10, 100, "bb")), "T",
                new ArrayList<>(), new HashSet<>(Arrays.asList("b.sav")),
                new CountingHasher(), true);
        assertEquals(1, p.items.size());
        assertEquals("b.sav", p.items.get(0).path);
    }
}
