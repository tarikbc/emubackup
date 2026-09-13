package com.tarikbc.emubackup;

/**
 * What kind of data a target holds. Drives defaults, retention, size policy and UI
 * labelling, so it is not cosmetic.
 *
 * <p>See {@code TARGETS.md} and {@code DESIGN.md §6}.
 */
public enum Category {
    /** Real game progress. The reason the app exists. Enabled by default. */
    SAVE,
    /**
     * Mid-session memory snapshots. Disposable by nature, frequently unloadable after an
     * emulator update, and disproportionately large — on the reference device they are
     * ~480 MB against ~250 MB of real saves. Always off by default.
     */
    STATE,
    /**
     * Console keys. Not save data, but a restore onto a fresh device will not boot without
     * them. Legally sensitive and stored unencrypted, so always off by default and always
     * marked {@code sensitive}.
     */
    KEY,
    /** Emulator settings. Convenient to keep, never required, off by default. */
    CONFIG;

    /** {@code true} for categories that must never be enabled without the user asking. */
    public boolean optInOnly() {
        return this != SAVE;
    }
}
