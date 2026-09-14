package com.tarikbc.emubackup;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Tier A writer. Every file lands as {@code <name>.ebtmp} and is renamed into place only once
 * it is fully written and verified, so an interrupted restore can never leave a truncated save
 * where a good one used to be.
 *
 * <p>Android-free by design; see {@code test.sh}.
 */
public final class LocalFileSink implements FileSink {

    static final String TEMP_SUFFIX = ".ebtmp";

    @Override public boolean available() {
        return true;
    }

    @Override public void mkdirs(String root, String relPath) throws IOException {
        File target = new File(PathResolver.join(root, relPath));
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("could not create " + parent.getPath());
        }
    }

    @Override public OutputStream createTemp(String root, String relPath) throws IOException {
        mkdirs(root, relPath);
        return new FileOutputStream(temp(root, relPath));
    }

    @Override public void commit(String root, String relPath) throws IOException {
        File tmp = temp(root, relPath);
        File dst = new File(PathResolver.join(root, relPath));
        if (!tmp.isFile()) throw new IOException("no staged file to commit for " + relPath);
        // renameTo will not replace an existing file on every filesystem, so clear the way
        // first. The window between delete and rename is why commit is only ever called after
        // the pre-restore snapshot has already captured the file being replaced.
        if (dst.exists() && !dst.delete()) {
            throw new IOException("could not replace " + dst.getPath());
        }
        if (!tmp.renameTo(dst)) {
            throw new IOException("could not commit " + tmp.getPath() + " -> " + dst.getPath());
        }
    }

    @Override public void discardTemp(String root, String relPath) {
        File tmp = temp(root, relPath);
        if (tmp.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    @Override public void setMtime(String root, String relPath, long epochMs) throws IOException {
        File f = new File(PathResolver.join(root, relPath));
        // A failed setLastModified is not fatal — the manifest is authoritative for mtime
        // (FORMAT.md section 5) — but it is worth knowing about, so it is reported.
        if (!f.setLastModified(epochMs)) {
            throw new IOException("could not set mtime on " + f.getPath());
        }
    }

    @Override public void delete(String root, String relPath) throws IOException {
        File f = new File(PathResolver.join(root, relPath));
        if (f.exists() && !f.delete()) throw new IOException("could not delete " + f.getPath());
    }

    private static File temp(String root, String relPath) {
        return new File(PathResolver.join(root, relPath) + TEMP_SUFFIX);
    }
}
