package com.tarikbc.emubackup;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DiffEngineTest {

    /** Counts reads so the "stat first, hash almost nothing" contract can be asserted, not assumed. */
    private static final class CountingHasher implements DiffEngine.Hasher {
        final java.util.Map<String, String> hashes = new java.util.HashMap<>();
        int calls;

        CountingHasher put(String path, String hash) { hashes.put(path, hash); return this; }

        @Override public String sha256(String relPath) {
            calls++;
            String h = hashes.get(relPath);
            if (h == null) throw new IllegalStateException("test did not define a hash for " + relPath);
            return h;
        }
    }

    private static FileStat f(String p, long size, long mtime) { return new FileStat(p, size, mtime); }

    private static ManifestFile m(String p, long size, long mtime, String hash, String ver) {
        return new ManifestFile(p, size, mtime, hash, ver);
    }

    @Test
    @DisplayName("a first run archives everything and hashes it once each")
    void firstRun() throws Exception {
        CountingHasher h = new CountingHasher().put("a.sav", "aa").put("b.sav", "bb");
        Plan p = DiffEngine.diff("t", Arrays.asList(f("a.sav", 10, 100), f("b.sav", 20, 200)),
                null, h, "v0001");

        assertEquals(2, p.addedCount);
        assertEquals(2, p.toArchive.size());
        assertEquals(2, h.calls);
        for (ManifestFile mf : p.files) assertEquals("v0001", mf.version);
    }

    @Test
    @DisplayName("same size and same mtime is unchanged, with no read at all")
    void unchangedIsNeverRead() throws Exception {
        // This is the fast path that skips hundreds of megabytes of untouched save states.
        CountingHasher h = new CountingHasher();
        List<ManifestFile> prior = Arrays.asList(m("a.sav", 10, 100, "aa", "v0001"));
        Plan p = DiffEngine.diff("t", Arrays.asList(f("a.sav", 10, 100)), prior, h, "v0002");

        assertEquals(0, h.calls, "an unchanged file must not be read");
        assertEquals(1, p.unchangedCount);
        assertTrue(p.toArchive.isEmpty());
        assertEquals("v0001", p.files.get(0).version, "the entry must still point at the old archive");
    }

    @Test
    @DisplayName("a different size is archived without needing the old content")
    void changedSize() throws Exception {
        CountingHasher h = new CountingHasher().put("a.sav", "cc");
        List<ManifestFile> prior = Arrays.asList(m("a.sav", 10, 100, "aa", "v0001"));
        Plan p = DiffEngine.diff("t", Arrays.asList(f("a.sav", 99, 100)), prior, h, "v0002");

        assertEquals(1, p.changedCount);
        assertEquals(1, p.toArchive.size());
        assertEquals("v0002", p.files.get(0).version);
    }

    @Test
    @DisplayName("a touched file with identical content is hashed but not archived")
    void rehashedButUnchanged() throws Exception {
        // Emulators rewrite whole save files on exit, so mtimes churn while bytes do not.
        // Without this check every run would re-upload the entire Switch save tree.
        CountingHasher h = new CountingHasher().put("a.sav", "aa");
        List<ManifestFile> prior = Arrays.asList(m("a.sav", 10, 100, "aa", "v0001"));
        Plan p = DiffEngine.diff("t", Arrays.asList(f("a.sav", 10, 555)), prior, h, "v0002");

        assertEquals(1, h.calls);
        assertEquals(1, p.rehashedCount);
        assertEquals(1, p.unchangedCount);
        assertTrue(p.toArchive.isEmpty(), "identical content must not be rewritten");
        assertEquals("v0001", p.files.get(0).version, "bytes still live in the old archive");
        assertEquals(555, p.files.get(0).mtimeMs,
                "the new mtime must be recorded so the next run takes the fast path");
    }

    @Test
    @DisplayName("a file that is gone is recorded as deleted")
    void deleted() throws Exception {
        CountingHasher h = new CountingHasher();
        List<ManifestFile> prior = Arrays.asList(m("a.sav", 10, 100, "aa", "v0001"),
                m("b.sav", 10, 100, "bb", "v0001"));
        Plan p = DiffEngine.diff("t", Arrays.asList(f("a.sav", 10, 100)), prior, h, "v0002");

        assertEquals(Arrays.asList("b.sav"), p.deleted);
        assertEquals(1, p.files.size());
    }

    @Test
    @DisplayName("nothing changed at all is a no-op version for this target")
    void noOp() throws Exception {
        List<ManifestFile> prior = Arrays.asList(m("a.sav", 10, 100, "aa", "v0001"));
        Plan p = DiffEngine.diff("t", Arrays.asList(f("a.sav", 10, 100)), prior,
                new CountingHasher(), "v0002");
        assertTrue(p.isNoOp());
    }

    @Test
    @DisplayName("promoting to a full archive re-reads nothing")
    void asFullDoesNotRehash() throws Exception {
        CountingHasher h = new CountingHasher().put("b.sav", "bb");
        List<ManifestFile> prior = Arrays.asList(m("a.sav", 10, 100, "aa", "v0001"));
        List<FileStat> current = Arrays.asList(f("a.sav", 10, 100), f("b.sav", 20, 200));

        Plan inc = DiffEngine.diff("t", current, prior, h, "v0002");
        int callsAfterDiff = h.calls;
        Plan full = DiffEngine.asFull(inc, current, "v0002");

        assertEquals(callsAfterDiff, h.calls, "promotion must not read any file again");
        assertEquals(2, full.toArchive.size());
        for (ManifestFile mf : full.files) {
            assertEquals("v0002", mf.version, "a full archive owns every file's bytes");
        }
        // The carried-forward hash is still correct, because the content did not change.
        for (ManifestFile mf : full.files) {
            if (mf.path.equals("a.sav")) assertEquals("aa", mf.sha256);
        }
    }

    @Test
    @DisplayName("an empty target against an empty prior is a no-op, not an error")
    void emptyBoth() throws Exception {
        Plan p = DiffEngine.diff("t", new ArrayList<>(), new ArrayList<>(),
                new CountingHasher(), "v0001");
        assertTrue(p.isNoOp());
        assertTrue(p.files.isEmpty());
    }
}
