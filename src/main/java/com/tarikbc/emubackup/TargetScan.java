package com.tarikbc.emubackup;

import java.util.Collections;
import java.util.List;

/**
 * The result of scanning one target. Immutable.
 *
 * <p>Carries why it found nothing as well as what it found, because "no saves yet", "you have
 * not installed this emulator", "this needs Shizuku" and "we could not read it" are four
 * different situations and only one of them is a problem.
 *
 * <p>Android-free by design; see {@code test.sh}.
 */
public final class TargetScan {

    public final String targetId;
    public final TargetStatus status;

    /** Absolute, with {@code {EXT}}/{@code {DATA}} expanded. Null when resolution never happened. */
    public final String resolvedRoot;

    /** Files that matched. Empty for every status except {@link TargetStatus#OK}. */
    public final List<FileStat> files;

    public final long totalBytes;

    /** How many files under the root did not match the target's globs. */
    public final int unmatchedCount;

    /**
     * A short sample of non-matching paths, capped. Enough to diagnose a wrong glob without
     * holding thousands of ROM filenames in memory — the melonDS root alone sits beside
     * gigabytes of archives.
     */
    public final List<String> unmatchedSample;

    /** Populated for {@link TargetStatus#UNREADABLE} and {@link TargetStatus#OVER_CAP}. */
    public final String detail;

    static final int UNMATCHED_SAMPLE_CAP = 20;

    TargetScan(String targetId, TargetStatus status, String resolvedRoot, List<FileStat> files,
               long totalBytes, int unmatchedCount, List<String> unmatchedSample, String detail) {
        this.targetId = targetId;
        this.status = status;
        this.resolvedRoot = resolvedRoot;
        this.files = Collections.unmodifiableList(files);
        this.totalBytes = totalBytes;
        this.unmatchedCount = unmatchedCount;
        this.unmatchedSample = Collections.unmodifiableList(unmatchedSample);
        this.detail = detail;
    }

    public int fileCount() {
        return files.size();
    }

    /** Whether this scan produced anything worth archiving. */
    public boolean hasContent() {
        return status == TargetStatus.OK && !files.isEmpty();
    }

    @Override public String toString() {
        return targetId + " " + status + " " + files.size() + " files / " + totalBytes + "b";
    }
}
