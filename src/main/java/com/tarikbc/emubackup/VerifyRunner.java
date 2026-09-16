package com.tarikbc.emubackup;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks that a stored version could actually be restored.
 *
 * <p>Writing a backup and reporting success says the bytes left the device. It does not say they
 * are still there, still whole, or still reachable. Those are different claims, and the gap
 * between them is where a backup tool quietly stops being one. This is the check that closes it.
 *
 * <p>Two things are verified, and they fail in different ways:
 *
 * <ul>
 *   <li><b>Every archive the chains reference exists.</b> An incremental extracts from the full
 *       beneath it, which may live in a much older version. A missing link means this version
 *       restores to nothing, and nothing else would have noticed.</li>
 *   <li><b>Every archive still hashes to what its manifest recorded.</b> Catches truncation, a
 *       partial upload that was never finished, bit rot, and a file replaced by something else.</li>
 * </ul>
 *
 * <p>The hash for an archive belongs to the manifest of the version that <em>wrote</em> it, not to
 * the version being verified, so those manifests are read too. That is the cost of chained
 * incrementals and it is bounded by the length of the chain.
 *
 * <p>This reads every archive in full. On a Drive store that means downloading the whole version.
 * The caller says so before starting; there is no cheaper way to check bytes are intact, and a
 * check that does not read the bytes is not a check.
 *
 * <p>Android-free by design; see {@code test.sh}.
 */
public final class VerifyRunner {

    public interface Listener {
        void onProgress(String archive, int done, int total);
        boolean isCancelled();

        Listener SILENT = new Listener() {
            @Override public void onProgress(String a, int d, int t) { }
            @Override public boolean isCancelled() { return false; }
        };
    }

    public static final class Result {
        public final String versionId;
        public final int archivesChecked;
        public final int archivesExpected;
        public final long bytesRead;
        public final boolean cancelled;

        /** Empty when the version is sound. One line each, naming the archive. */
        public final List<String> problems;

        Result(String versionId, int checked, int expected, long bytesRead, boolean cancelled,
               List<String> problems) {
            this.versionId = versionId;
            this.archivesChecked = checked;
            this.archivesExpected = expected;
            this.bytesRead = bytesRead;
            this.cancelled = cancelled;
            this.problems = problems;
        }

        public boolean ok() {
            return !cancelled && problems.isEmpty() && archivesChecked == archivesExpected;
        }

        public String summary() {
            if (cancelled) return "Stopped. Nothing is proven either way.";
            if (ok()) {
                return archivesChecked + (archivesChecked == 1 ? " archive" : " archives")
                        + " checked, " + Sizes.human(bytesRead) + " read. Every one matches.";
            }
            return problems.size() + (problems.size() == 1 ? " problem" : " problems")
                    + " in " + archivesExpected + " archives. This backup is not sound.";
        }
    }

    private VerifyRunner() {}

    public static Result verify(BackupSink store, String versionId, Listener listener)
            throws IOException {
        Listener l = listener == null ? Listener.SILENT : listener;
        List<String> problems = new ArrayList<>();

        Manifest m = readManifest(store, versionId);

        // Every "<version>/<archive>" any target needs, in a stable order.
        Set<String> links = new LinkedHashSet<>();
        for (ManifestTarget t : m.targets) links.addAll(t.chain);

        // The recorded hash for each link, taken from the manifest of the version that wrote it.
        Map<String, String> expected = new LinkedHashMap<>();
        Map<String, Manifest> manifests = new HashMap<>();
        manifests.put(versionId, m);

        for (String link : links) {
            int slash = link.indexOf('/');
            if (slash <= 0) {
                problems.add("Malformed chain entry in the manifest: " + link);
                continue;
            }
            String v = link.substring(0, slash);
            String archive = link.substring(slash + 1);

            Manifest owner = manifests.get(v);
            if (owner == null) {
                try {
                    owner = readManifest(store, v);
                    manifests.put(v, owner);
                } catch (Exception e) {
                    // The manifest of a version this one depends on is gone. Retention is written
                    // so this cannot happen; seeing it means something else removed it.
                    problems.add(link + ": the version it comes from has no readable manifest");
                    continue;
                }
            }

            String sha = null;
            for (ManifestTarget t : owner.targets) {
                if (archive.equals(t.archive)) {
                    sha = t.archiveSha256;
                    break;
                }
            }
            if (sha == null) {
                problems.add(link + ": no recorded checksum, so it cannot be checked");
                continue;
            }
            expected.put(link, sha);
        }

        int total = expected.size();
        int done = 0;
        long bytes = 0;

        for (Map.Entry<String, String> e : expected.entrySet()) {
            if (l.isCancelled()) {
                return new Result(versionId, done, total, bytes, true, problems);
            }
            String link = e.getKey();
            int slash = link.indexOf('/');
            String v = link.substring(0, slash);
            String archive = link.substring(slash + 1);

            l.onProgress(archive, done, total);

            if (!store.hasFile(v, archive)) {
                problems.add(link + ": missing. This version cannot be restored without it.");
                done++;
                continue;
            }

            String actual;
            CountingStream counter;
            try (InputStream in = store.openFile(v, archive)) {
                counter = new CountingStream(in);
                actual = Hashes.sha256(counter);
            } catch (Exception ex) {
                problems.add(link + ": could not be read (" + message(ex) + ")");
                done++;
                continue;
            }
            bytes += counter.count;

            if (!e.getValue().equalsIgnoreCase(actual)) {
                problems.add(link + ": checksum does not match. The stored copy has changed "
                        + "since it was written.");
            }
            done++;
        }

        l.onProgress(null, done, total);
        return new Result(versionId, done, total, bytes, false, problems);
    }

    private static Manifest readManifest(BackupSink store, String versionId) throws IOException {
        try (InputStream in = store.openFile(versionId, "manifest.json")) {
            return Manifest.fromJson(BackupRunner.readAll(in));
        }
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /** Counts what was read, so the report can say how much was actually checked. */
    private static final class CountingStream extends java.io.FilterInputStream {
        long count;

        CountingStream(InputStream in) {
            super(in);
        }

        @Override public int read() throws IOException {
            int b = super.read();
            if (b != -1) count++;
            return b;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) count += n;
            return n;
        }
    }
}
