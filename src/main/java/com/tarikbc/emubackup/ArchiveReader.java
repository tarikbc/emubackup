package com.tarikbc.emubackup;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Pulls files back out of a chain of archives.
 *
 * <p>Rather than extracting every archive in order and letting later ones overwrite earlier ones,
 * this asks the manifest which version actually holds each wanted file and opens only the archives
 * that carry something needed. A hand restore does it the naive way — that is what
 * {@code RESTORE.txt} describes and it produces the same tree — but doing redundant writes over
 * real save files is worth avoiding when it can be avoided exactly.
 *
 * <p>Android-free by design; see {@code test.sh}.
 */
public final class ArchiveReader {

    public interface Listener {
        void onFile(String path, int index, int total, long bytesDone, long bytesTotal);
        boolean isCancelled();
    }

    public static final Listener SILENT = new Listener() {
        @Override public void onFile(String p, int i, int n, long d, long t) {}
        @Override public boolean isCancelled() { return false; }
    };

    /** Receives each extracted file's bytes. */
    public interface Writer {
        OutputStream open(String relPath) throws IOException;
        /** Called after the stream is closed and the content verified. */
        void commit(String relPath, ManifestFile expected) throws IOException;
        void discard(String relPath);
    }

    public static final class Result {
        public final int written;
        public final long bytes;
        public final boolean cancelled;
        public final List<String> missing;
        public final List<String> corrupt;

        Result(int written, long bytes, boolean cancelled, List<String> missing, List<String> corrupt) {
            this.written = written;
            this.bytes = bytes;
            this.cancelled = cancelled;
            this.missing = missing;
            this.corrupt = corrupt;
        }

        public boolean ok() {
            return !cancelled && missing.isEmpty() && corrupt.isEmpty();
        }
    }

    /**
     * @param chain  archive references as {@code "<version>/<name>.zip"}, oldest first
     * @param wanted the files to restore, keyed by target-relative path
     */
    public static Result extract(BackupSink sink, List<String> chain, Map<String, ManifestFile> wanted,
                                 Writer writer, Listener listener) throws IOException {
        // Group by the archive that actually holds each file, so no archive is opened for nothing
        // and no file is written twice.
        Map<String, Map<String, ManifestFile>> byArchive = new LinkedHashMap<>();
        for (String ref : chain) byArchive.put(ref, new LinkedHashMap<>());

        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, ManifestFile> e : wanted.entrySet()) {
            String ref = refFor(chain, e.getValue().version);
            if (ref == null) {
                // The manifest names a version that is not in this target's chain. Recorded rather
                // than skipped silently: a file we cannot locate is exactly what a restore must
                // not gloss over.
                missing.add(e.getKey());
                continue;
            }
            byArchive.get(ref).put(e.getKey(), e.getValue());
        }

        long bytesTotal = 0;
        for (ManifestFile f : wanted.values()) bytesTotal += f.size;

        int written = 0, index = 0;
        long bytesDone = 0;
        List<String> corrupt = new ArrayList<>();
        boolean cancelled = false;

        for (Map.Entry<String, Map<String, ManifestFile>> entry : byArchive.entrySet()) {
            Map<String, ManifestFile> want = entry.getValue();
            if (want.isEmpty()) continue;

            String ref = entry.getKey();
            int slash = ref.indexOf('/');
            String versionId = ref.substring(0, slash);
            String archive = ref.substring(slash + 1);

            try (InputStream raw = sink.openFile(versionId, archive);
                 ZipInputStream zin = new ZipInputStream(raw)) {
                ZipEntry ze;
                while ((ze = zin.getNextEntry()) != null) {
                    if (listener.isCancelled()) { cancelled = true; break; }
                    ManifestFile expected = want.remove(ze.getName());
                    if (expected == null) continue;

                    listener.onFile(ze.getName(), ++index, wanted.size(), bytesDone, bytesTotal);

                    String actual;
                    try (OutputStream out = writer.open(ze.getName())) {
                        actual = copyAndHash(zin, out);
                    }
                    if (!actual.equals(expected.sha256)) {
                        // The archive does not contain what the manifest says it does. Abandoning
                        // this file leaves the original in place, which is the safe direction.
                        writer.discard(ze.getName());
                        corrupt.add(ze.getName());
                        continue;
                    }
                    writer.commit(ze.getName(), expected);
                    written++;
                    bytesDone += expected.size;
                }
            }
            if (cancelled) break;
            missing.addAll(want.keySet());
        }

        return new Result(written, bytesDone, cancelled, missing, corrupt);
    }

    private static String refFor(List<String> chain, String versionId) {
        for (String ref : chain) {
            if (ref.startsWith(versionId + "/")) return ref;
        }
        return null;
    }

    private static String copyAndHash(InputStream in, OutputStream out) throws IOException {
        java.security.MessageDigest md;
        try {
            md = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            md.update(buf, 0, n);
        }
        return Hashes.hex(md.digest());
    }

    private ArchiveReader() {}
}
