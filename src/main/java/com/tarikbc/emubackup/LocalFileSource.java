package com.tarikbc.emubackup;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Tier A: shared storage, reached with plain {@code java.io.File} under
 * {@code MANAGE_EXTERNAL_STORAGE}.
 *
 * <p>Covers about 700 MB of the ~730 MB of save data on the reference device, including the
 * Eden saves this app was written for. Deliberately free of {@code android.*}, which is what
 * lets the entire scan, diff, archive and restore pipeline be tested on a JVM against a temp
 * directory with no emulator and no device.
 */
public final class LocalFileSource implements FileSource {

    /** Guards against a pathological tree or an undetected link loop hanging a scan. */
    private static final int MAX_DEPTH = 64;

    @Override public boolean available() {
        return true;
    }

    @Override public boolean exists(String root) {
        return new File(root).isDirectory();
    }

    @Override public List<FileStat> walk(String root, boolean recursive) throws IOException {
        File base = new File(root);
        if (!base.isDirectory()) throw new IOException("not a directory: " + root);

        List<FileStat> out = new ArrayList<>();
        Deque<Entry> stack = new ArrayDeque<>();
        stack.push(new Entry(base, "", 0));

        while (!stack.isEmpty()) {
            Entry e = stack.pop();
            File[] kids = e.dir.listFiles();
            if (kids == null) {
                // Unreadable directory. Surfaced rather than swallowed: a target that silently
                // returns nothing looks identical to a target that is genuinely empty, and the
                // user would believe they were covered.
                throw new IOException("cannot list directory: " + e.dir.getPath());
            }
            for (File f : kids) {
                String rel = e.rel.isEmpty() ? f.getName() : e.rel + "/" + f.getName();
                if (isSymlink(f)) continue;
                if (f.isDirectory()) {
                    if (recursive && e.depth < MAX_DEPTH) stack.push(new Entry(f, rel, e.depth + 1));
                } else if (f.isFile()) {
                    out.add(new FileStat(rel, f.length(), f.lastModified()));
                }
            }
        }
        return out;
    }

    @Override public InputStream open(String root, String relPath) throws IOException {
        return new FileInputStream(new File(PathResolver.join(root, relPath)));
    }

    /**
     * Symlinks are skipped, not followed. A link pointing back up its own tree would otherwise
     * make a scan loop, and a link pointing outside the target root would pull unrelated files
     * into a backup.
     */
    private static boolean isSymlink(File f) {
        try {
            return java.nio.file.Files.isSymbolicLink(f.toPath());
        } catch (RuntimeException e) {
            // Path conversion can throw on names the default filesystem rejects. Treating those
            // as links skips them, which is the conservative choice.
            return true;
        }
    }

    private static final class Entry {
        final File dir; final String rel; final int depth;
        Entry(File dir, String rel, int depth) { this.dir = dir; this.rel = rel; this.depth = depth; }
    }
}
