package com.tarikbc.emubackup;

/**
 * One file as recorded in a manifest.
 *
 * <p>{@link #version} is the chain-resolution mechanism: it names the version whose archive
 * actually holds these bytes, which may be older than the manifest the entry appears in.
 * Without it an incremental backup is not restorable. See {@code FORMAT.md} section 6.
 */
public final class ManifestFile {

    /** Target-relative path. */
    public final String path;
    public final long size;

    /** Exact epoch milliseconds. The manifest is authoritative for mtime, not the zip entry. */
    public final long mtimeMs;

    public final String sha256;

    /** The version id whose archive contains this file's bytes. */
    public final String version;

    public ManifestFile(String path, long size, long mtimeMs, String sha256, String version) {
        this.path = path;
        this.size = size;
        this.mtimeMs = mtimeMs;
        this.sha256 = sha256;
        this.version = version;
    }

    /** The same entry carried into a newer manifest, still pointing at the older archive. */
    public ManifestFile carriedForward(long newMtimeMs) {
        return new ManifestFile(path, size, newMtimeMs, sha256, version);
    }

    @Override public String toString() {
        return path + " @" + version;
    }
}
