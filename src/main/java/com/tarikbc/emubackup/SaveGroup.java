package com.tarikbc.emubackup;

import java.util.Collections;
import java.util.List;

/**
 * One game's saves within a target, optionally under one profile.
 *
 * <p>A view over a file list rather than anything the archive format knows about, which is why
 * per-game restore needed no format change: selecting a group just narrows which entries are
 * pulled out of the same whole archives.
 */
public final class SaveGroup {

    /**
     * Sentinel for files a target's grouping rule did not match. Never dropped, because a file
     * quietly missing from the UI is a file the user believes is covered and is not.
     *
     * <p>Starts with a character that no normalised identifier can begin with, so it cannot
     * collide with a real game key.
     */
    public static final String UNGROUPED = "!ungrouped";

    public final String targetId;

    /** Null when the target has no profile axis. */
    public final String profileKey;

    /** {@link #UNGROUPED}, or null when the target is not splittable at all. */
    public final String gameKey;

    public final IdKind gameIdKind;
    public final List<FileStat> files;
    public final long bytes;
    public final long newestMtimeMs;

    SaveGroup(String targetId, String profileKey, String gameKey, IdKind gameIdKind,
              List<FileStat> files) {
        this.targetId = targetId;
        this.profileKey = profileKey;
        this.gameKey = gameKey;
        this.gameIdKind = gameIdKind;
        this.files = Collections.unmodifiableList(files);
        long b = 0, newest = 0;
        for (FileStat f : files) {
            b += f.size;
            newest = Math.max(newest, f.mtimeMs);
        }
        this.bytes = b;
        this.newestMtimeMs = newest;
    }

    /** Stable identity within a target, used as a selection key. */
    public String key() {
        return (profileKey == null ? "" : profileKey + "/") + (gameKey == null ? "*" : gameKey);
    }

    public boolean isUngrouped() {
        return UNGROUPED.equals(gameKey);
    }

    /** Whole-target group, for a target that cannot meaningfully be split. */
    public boolean isWholeTarget() {
        return gameKey == null;
    }

    @Override public String toString() {
        return targetId + ":" + key() + " (" + files.size() + " files)";
    }
}
