package com.tarikbc.emubackup;

/**
 * What a restore would do to one file.
 *
 * <p>The distinctions exist because restore is the one operation in this app that can destroy
 * data. Lumping them together — "N files will be written" — is what turns "I restored an old
 * backup" into "I lost this week's progress".
 */
public enum RestoreAction {
    /** Absent on the device. Safe. */
    CREATE,
    /** Present with identical content. Nothing to do. */
    SKIP_IDENTICAL,
    /** Present, older than the backup, content differs. The ordinary case. */
    OVERWRITE_OLDER,
    /**
     * Present and <em>newer</em> than the backup. Skipped unless explicitly forced, per target,
     * with the count and the newest timestamp shown. This is the case that loses progress.
     */
    CONFLICT_NEWER,
    /** Same timestamp, different content. Something wrote without updating mtime; treat as suspect. */
    SIZE_CONFLICT,
    /** On the device, absent from the backup. Never deleted by default. */
    ORPHAN_ON_DEVICE,
    /** In an app-private target with no Shizuku. Cannot be written at all. */
    BLOCKED_TIER;

    /** Whether this file is written when the user has not forced anything. */
    public boolean appliesByDefault() {
        return this == CREATE || this == OVERWRITE_OLDER;
    }

    /** Whether forcing this target would make it apply. */
    public boolean forceable() {
        return this == CONFLICT_NEWER || this == SIZE_CONFLICT;
    }
}
