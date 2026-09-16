package com.tarikbc.emubackup;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes one target's archive as a plain zip whose entries are target-relative.
 *
 * <p>Plain zip, plain relative paths, no container of our own: a person with nothing but
 * {@code unzip} must be able to put their saves back. That constraint rules out otherwise
 * better-compressing designs and is the reason this class is as boring as it is. See
 * {@code FORMAT.md} section 1.
 *
 * <p>Android-free by design; see {@code test.sh}.
 */
public final class ArchiveWriter {

    /** Reports progress and lets a long run be cancelled between files. */
    public interface Listener {
        void onFile(String path, int index, int total, long bytesDone, long bytesTotal);
        boolean isCancelled();
    }

    public static final Listener SILENT = new Listener() {
        @Override public void onFile(String p, int i, int n, long d, long t) {}
        @Override public boolean isCancelled() { return false; }
    };

    public static final class Result {
        public final int files;
        public final long archiveBytes;
        public final String sha256;
        public final boolean cancelled;

        /** Files that could be listed but not opened. Reported, never fatal. */
        public final java.util.List<String> unreadable;

        Result(int files, long archiveBytes, String sha256, boolean cancelled,
               java.util.List<String> unreadable) {
            this.files = files;
            this.archiveBytes = archiveBytes;
            this.sha256 = sha256;
            this.cancelled = cancelled;
            this.unreadable = unreadable;
        }
    }

    /**
     * @param store when true the payload is already compressed, so no effort is spent deflating
     *              it. Implemented as deflate at level 0 rather than the zip STORED method: the
     *              result is still an ordinary zip any tool can open, and it avoids having to
     *              compute a CRC up front for every entry.
     */
    public static Result write(OutputStream out, String root, FileSource src, Plan plan,
                               boolean store, Listener listener) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }

        CountingStream counter = new CountingStream(out);
        DigestOutputStream digest = new DigestOutputStream(counter, md);
        long bytesTotal = plan.archiveBytes(), bytesDone = 0;
        int i = 0, total = plan.toArchive.size();
        boolean cancelled = false;

        java.util.List<String> unreadable = new java.util.ArrayList<>();
        ZipOutputStream zip = new ZipOutputStream(digest);
        zip.setLevel(store ? Deflater.NO_COMPRESSION : Deflater.BEST_SPEED);
        try {
            for (FileStat f : plan.toArchive) {
                if (listener.isCancelled()) {
                    cancelled = true;
                    break;
                }
                listener.onFile(f.path, ++i, total, bytesDone, bytesTotal);

                // Opened before the entry is created. A file that cannot be read must leave no
                // trace in the archive, and an entry opened and then abandoned would.
                InputStream in;
                try {
                    in = src.open(root, f.path);
                } catch (IOException ex) {
                    // Genuinely unreadable, not a bug: DuckStation writes its memory cards mode
                    // 600, which even the shell identity cannot open. Losing every other save
                    // over this one file would be far worse than an honest, reported gap.
                    unreadable.add(f.path);
                    i--;
                    continue;
                }

                ZipEntry e = new ZipEntry(f.path);
                // Best effort only. Zip timestamps are two-second granular with no timezone, so
                // the manifest stays authoritative for mtime; this just makes a hand restore land
                // in the right ballpark. See FORMAT.md section 5.
                e.setLastModifiedTime(java.nio.file.attribute.FileTime.fromMillis(f.mtimeMs));
                zip.putNextEntry(e);
                try {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        zip.write(buf, 0, n);
                        bytesDone += n;
                    }
                } finally {
                    in.close();
                }
                zip.closeEntry();
            }
            zip.finish();
        } finally {
            zip.flush();
        }

        return new Result(i, counter.count, Hashes.hex(md.digest()), cancelled, unreadable);
    }

    /** Counts bytes actually written, which is the archive's size on disk. */
    private static final class CountingStream extends OutputStream {
        private final OutputStream out;
        long count;

        CountingStream(OutputStream out) { this.out = out; }

        @Override public void write(int b) throws IOException { out.write(b); count++; }

        @Override public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }

        @Override public void flush() throws IOException { out.flush(); }

        // Deliberately does not close the delegate: the caller owns it and may still need to
        // finalise the file (rename a temp into place, finish an upload).
        @Override public void close() throws IOException { out.flush(); }
    }

    private ArchiveWriter() {}
}
