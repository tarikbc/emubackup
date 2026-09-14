package com.tarikbc.emubackup;

/**
 * An immutable snapshot of a running backup.
 *
 * <p>Immutable because it crosses a thread boundary on every update and is read by whichever
 * Activity happens to be on screen, which may be none.
 */
public final class Progress {

    public enum Phase { SCANNING, DIFFING, ARCHIVING, WRITING_MANIFEST, DONE, CANCELLED, FAILED }

    public final Phase phase;
    public final String targetId;
    public final String targetLabel;
    public final int targetIndex, targetCount;
    public final String fileName;
    public final int filesDone, filesTotal;
    public final long bytesDone, bytesTotal;
    public final String message;

    public Progress(Phase phase, String targetId, String targetLabel, int targetIndex, int targetCount,
                    String fileName, int filesDone, int filesTotal, long bytesDone, long bytesTotal,
                    String message) {
        this.phase = phase;
        this.targetId = targetId;
        this.targetLabel = targetLabel;
        this.targetIndex = targetIndex;
        this.targetCount = targetCount;
        this.fileName = fileName;
        this.filesDone = filesDone;
        this.filesTotal = filesTotal;
        this.bytesDone = bytesDone;
        this.bytesTotal = bytesTotal;
        this.message = message;
    }

    public static Progress of(Phase phase, String message) {
        return new Progress(phase, null, null, 0, 0, null, 0, 0, 0, 0, message);
    }

    /** Overall completion, or -1 when the total is not yet known. */
    public int percent() {
        if (bytesTotal <= 0) return -1;
        return (int) Math.min(100, bytesDone * 100 / bytesTotal);
    }
}
