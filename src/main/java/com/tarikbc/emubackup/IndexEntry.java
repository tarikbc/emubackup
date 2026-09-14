package com.tarikbc.emubackup;

/** One line in the store index. See {@code FORMAT.md} section 8. */
public final class IndexEntry {

    public final String id;
    public final long createdAtMs;
    public final long bytes;
    public final boolean pinned;

    /** {@code "manual"}, {@code "scheduled"} or {@code "prerestore"}. */
    public final String kind;

    public IndexEntry(String id, long createdAtMs, long bytes, boolean pinned, String kind) {
        this.id = id;
        this.createdAtMs = createdAtMs;
        this.bytes = bytes;
        this.pinned = pinned;
        this.kind = kind;
    }

    public boolean isPreRestore() {
        return "prerestore".equals(kind);
    }
}
