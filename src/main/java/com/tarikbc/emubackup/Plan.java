package com.tarikbc.emubackup;

import java.util.Collections;
import java.util.List;

/**
 * What one target's backup run has to do: which files' bytes must be written this time, and
 * what the complete manifest file list looks like afterwards.
 *
 * <p>{@link #files} always describes the target in full, including entries whose bytes live in
 * an older version's archive. {@link #toArchive} is only the subset that has to be copied now.
 */
public final class Plan {

    public final String targetId;

    /** Files whose bytes must go into this version's archive. */
    public final List<FileStat> toArchive;

    /** The complete manifest entry list for this target after the run. */
    public final List<ManifestFile> files;

    /** Paths present in the previous manifest and gone now. */
    public final List<String> deleted;

    public final int addedCount;
    public final int changedCount;
    public final int unchangedCount;

    /**
     * Files whose modification time moved but whose content proved identical. Counted separately
     * because it is the number that justifies hashing at all: emulators rewrite whole save files
     * on exit, so without the content check every run would re-upload data that did not change.
     */
    public final int rehashedCount;

    Plan(String targetId, List<FileStat> toArchive, List<ManifestFile> files, List<String> deleted,
         int addedCount, int changedCount, int unchangedCount, int rehashedCount) {
        this.targetId = targetId;
        this.toArchive = Collections.unmodifiableList(toArchive);
        this.files = Collections.unmodifiableList(files);
        this.deleted = Collections.unmodifiableList(deleted);
        this.addedCount = addedCount;
        this.changedCount = changedCount;
        this.unchangedCount = unchangedCount;
        this.rehashedCount = rehashedCount;
    }

    public long archiveBytes() {
        long n = 0;
        for (FileStat f : toArchive) n += f.size;
        return n;
    }

    public long totalBytes() {
        long n = 0;
        for (ManifestFile f : files) n += f.size;
        return n;
    }

    /** Nothing to write and nothing removed, so this version need not contain the target at all. */
    public boolean isNoOp() {
        return toArchive.isEmpty() && deleted.isEmpty();
    }

    @Override public String toString() {
        return targetId + " +" + addedCount + " ~" + changedCount + " =" + unchangedCount
                + " -" + deleted.size() + " (" + archiveBytes() + "b to write)";
    }
}
