package com.tarikbc.emubackup;

/**
 * The outcome of scanning one target. Recorded in every manifest.
 *
 * <p>Distinguishing these matters for more than presentation: a later diff has to tell
 * "this emulator started being used" apart from "this was never present", and a user has to
 * be able to tell "nothing to back up" apart from "we could not look".
 */
public enum TargetStatus {
    /** Files found and archivable. */
    OK,
    /** The root exists but holds no matching files. Normal for an unused emulator. */
    EMPTY,
    /** The root does not exist. Normal before an emulator's first launch. */
    ROOT_MISSING,
    /** No package from the emulator's candidate list is installed. */
    PKG_NOT_INSTALLED,
    /** Tier B target with no Shizuku. Skipped, never an error. */
    TIER_UNAVAILABLE,
    /**
     * Matched more than {@code maxBytes}. The target is refused outright rather than
     * truncated — a partial backup that looks complete is worse than a visible refusal.
     */
    OVER_CAP,
    /** The root exists but could not be read (permissions, I/O). Genuinely a failure. */
    UNREADABLE;

    /** Whether this status should be reported to the user as a problem needing action. */
    public boolean isProblem() {
        return this == OVER_CAP || this == UNREADABLE;
    }
}
