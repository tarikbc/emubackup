package com.tarikbc.emubackup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * This is the only code in the app that deletes a backup, so the tests are about what it must
 * refuse to do, not about what it does.
 */
class RetentionPolicyTest {

    private static final long DAY = 86_400_000L;

    private static IndexEntry v(int n, long bytes, String... deps) {
        return new IndexEntry(id(n), n * DAY, bytes, false, "scheduled", Arrays.asList(deps));
    }

    private static IndexEntry pinned(int n) {
        return new IndexEntry(id(n), n * DAY, 1000, true, "prerestore", new ArrayList<>());
    }

    /** An entry as written before index version 2: no dependency information at all. */
    private static IndexEntry legacy(int n) {
        return new IndexEntry(id(n), n * DAY, 1000, false, "manual");
    }

    private static String id(int n) {
        return String.format("v%04d-2026091%d-1200", n, n % 10);
    }

    private static List<IndexEntry> list(IndexEntry... e) {
        return new ArrayList<>(Arrays.asList(e));
    }

    @Test
    @DisplayName("a store with one version is never pruned to nothing")
    void neverEmpties() {
        assertTrue(new RetentionPolicy(1, 0).toDelete(list(v(1, 100))).isEmpty());
        assertTrue(new RetentionPolicy(0, 1).toDelete(list(v(1, 10_000))).isEmpty());
    }

    @Test
    @DisplayName("UNLIMITED and a limit of zero are different things")
    void unlimitedIsNotZero() {
        List<IndexEntry> three = list(v(1, 100), v(2, 100), v(3, 100));
        assertTrue(new RetentionPolicy(RetentionPolicy.UNLIMITED, RetentionPolicy.UNLIMITED)
                .toDelete(three).isEmpty(), "UNLIMITED pruned something");
        assertEquals(2, new RetentionPolicy(0, RetentionPolicy.UNLIMITED).toDelete(three).size(),
                "a keep count of zero behaved as though it meant unlimited");
    }

    @Test
    @DisplayName("the newest version survives a keep count of zero")
    void newestAlwaysKept() {
        List<String> gone = new RetentionPolicy(0, RetentionPolicy.UNLIMITED)
                .toDelete(list(v(1, 100), v(2, 100), v(3, 100)));
        assertFalse(gone.contains(id(3)), "the newest version was deleted");
        assertEquals(2, gone.size());
    }

    @Test
    @DisplayName("over the count, the oldest go first")
    void prunesOldestFirst() {
        List<String> gone = new RetentionPolicy(2, RetentionPolicy.UNLIMITED)
                .toDelete(list(v(1, 100), v(2, 100), v(3, 100), v(4, 100)));
        assertEquals(Arrays.asList(id(1), id(2)), gone);
    }

    @Test
    @DisplayName("a full that a kept incremental extracts from is never deleted")
    void keepsTheBaseOfAChain() {
        // v4 is an incremental on v1. Keeping 2 would normally take v1 and v2.
        List<String> gone = new RetentionPolicy(2, RetentionPolicy.UNLIMITED)
                .toDelete(list(v(1, 5000), v(2, 100), v(3, 100), v(4, 100, id(1))));
        assertFalse(gone.contains(id(1)), "deleted the base of a chain v4 still needs");
        assertTrue(gone.contains(id(2)));
        assertTrue(gone.contains(id(3)));
    }

    @Test
    @DisplayName("a chain is followed all the way down, not one step")
    void keepsTransitively() {
        // v4 -> v3 -> v2 -> v1. Nothing may go, whatever the count says.
        List<String> gone = new RetentionPolicy(1, RetentionPolicy.UNLIMITED)
                .toDelete(list(v(1, 100), v(2, 100, id(1)), v(3, 100, id(2)), v(4, 100, id(3))));
        assertTrue(gone.isEmpty(), "broke a four-deep chain: " + gone);
    }

    @Test
    @DisplayName("a pre-restore snapshot is never pruned, and neither is what it needs")
    void pinnedIsUntouchable() {
        List<String> gone = new RetentionPolicy(1, RetentionPolicy.UNLIMITED)
                .toDelete(list(v(1, 100), pinned(2), v(3, 100), v(4, 100)));
        assertFalse(gone.contains(id(2)), "deleted a pre-restore snapshot");
        assertTrue(gone.contains(id(1)));
        assertTrue(gone.contains(id(3)));
    }

    @Test
    @DisplayName("an entry with no recorded dependencies protects everything older than it")
    void unknownDepsAreConservative() {
        // Written by an older build, so the chain is unknowable. Refusing to guess means the
        // two versions below it stay until it ages out on its own.
        List<String> gone = new RetentionPolicy(1, RetentionPolicy.UNLIMITED)
                .toDelete(list(v(1, 100), v(2, 100), legacy(3)));
        assertTrue(gone.isEmpty(), "pruned underneath an entry of unknown shape: " + gone);
    }

    @Test
    @DisplayName("a byte budget prunes the oldest until it fits")
    void budgetPrunesOldest() {
        List<String> gone = new RetentionPolicy(RetentionPolicy.UNLIMITED, 250)
                .toDelete(list(v(1, 100), v(2, 100), v(3, 100)));
        assertEquals(Arrays.asList(id(1)), gone);
    }

    @Test
    @DisplayName("a budget smaller than the newest version does not delete it")
    void budgetCannotEmptyTheStore() {
        List<String> gone = new RetentionPolicy(RetentionPolicy.UNLIMITED, 10)
                .toDelete(list(v(1, 100), v(2, 100), v(3, 9000)));
        assertFalse(gone.contains(id(3)));
        assertEquals(2, gone.size());
    }

    @Test
    @DisplayName("count and budget together never delete something protected")
    void bothLimitsRespectProtection() {
        List<String> gone = new RetentionPolicy(1, 1)
                .toDelete(list(v(1, 9000), pinned(2), v(3, 9000, id(1))));
        assertTrue(gone.isEmpty(), "a tight count and budget deleted protected versions: " + gone);
    }

    @Test
    @DisplayName("an out-of-order list is still pruned by age, not by position")
    void sortsBeforeDeciding() {
        List<String> gone = new RetentionPolicy(2, RetentionPolicy.UNLIMITED)
                .toDelete(list(v(3, 100), v(1, 100), v(4, 100), v(2, 100)));
        assertEquals(Arrays.asList(id(1), id(2)), gone);
    }

    @Test
    @DisplayName("a dependency on a version that is not in the index is ignored, not fatal")
    void danglingDependency() {
        List<String> gone = new RetentionPolicy(1, RetentionPolicy.UNLIMITED)
                .toDelete(list(v(1, 100), v(2, 100), v(3, 100, "v0099-20260101-0000")));
        assertEquals(Arrays.asList(id(1), id(2)), gone);
    }

    @Test
    @DisplayName("dependencies are read out of a manifest's chains, not guessed")
    void dependenciesOfManifest() {
        ManifestTarget a = target("eden-saves", Arrays.asList(
                "v0001-20260101-0000/eden-saves.full.zip",
                "v0003-20260103-0000/eden-saves.inc.zip"));
        ManifestTarget b = target("ps2-memcards", Arrays.asList(
                "v0003-20260103-0000/ps2-memcards.full.zip"));
        Manifest m = new Manifest("v0003-20260103-0000", 0, "1.0", 1, null, "kalama", 34,
                "/storage/emulated/0", new Capabilities(true, true, true), Arrays.asList(a, b));

        // Its own version is not a dependency, and the duplicate collapses.
        assertEquals(Arrays.asList("v0001-20260101-0000"), IndexEntry.dependenciesOf(m));
    }

    private static ManifestTarget target(String id, List<String> chain) {
        return new ManifestTarget(id, "emu", Tier.SHARED, Category.SAVE, "/root",
                TargetStatus.OK, "incremental", null, id + ".inc.zip", "abc", 10, 10, 0,
                chain, null, 0, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), null);
    }
}
