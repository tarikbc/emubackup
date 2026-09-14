package com.tarikbc.emubackup;

/**
 * One file's metadata, with its path relative to the target root.
 *
 * <p>Paths are target-relative for a reason: they are what goes into zip entries and into the
 * manifest, so keeping them relative from the moment of discovery means no later stage has to
 * remember to strip a prefix. See {@code FORMAT.md} section 2.
 *
 * <p>Android-free by design; see {@code test.sh}.
 */
public final class FileStat {

    /** Target-relative, {@code /}-separated, no leading slash. */
    public final String path;
    public final long size;
    public final long mtimeMs;

    public FileStat(String path, long size, long mtimeMs) {
        if (path == null || path.isEmpty()) throw new IllegalArgumentException("path must not be empty");
        if (path.startsWith("/")) throw new IllegalArgumentException("path must be target-relative: " + path);
        if (size < 0) throw new IllegalArgumentException("negative size for " + path);
        this.path = path;
        this.size = size;
        this.mtimeMs = mtimeMs;
    }

    /** The final path component. Grouping rules that use {@code filenameRegex} match against this. */
    public String name() {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FileStat)) return false;
        FileStat f = (FileStat) o;
        return size == f.size && mtimeMs == f.mtimeMs && path.equals(f.path);
    }

    @Override public int hashCode() {
        int h = path.hashCode();
        h = 31 * h + (int) (size ^ (size >>> 32));
        h = 31 * h + (int) (mtimeMs ^ (mtimeMs >>> 32));
        return h;
    }

    @Override public String toString() {
        return path + " (" + size + "b @" + mtimeMs + ")";
    }
}
