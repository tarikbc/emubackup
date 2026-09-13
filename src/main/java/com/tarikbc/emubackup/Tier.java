package com.tarikbc.emubackup;

/**
 * Which storage mechanism reaches a target.
 *
 * <p>The split is forced by the platform, not by preference: Android 11 removed
 * {@code Android/data} access for normal apps, {@code MANAGE_EXTERNAL_STORAGE} does not
 * cover it, and SAF cannot target it. See {@code ARCHITECTURE.md} "Hard constraint".
 */
public enum Tier {
    /** Reachable with {@code MANAGE_EXTERNAL_STORAGE} and plain {@code java.io.File}. */
    SHARED,
    /** Under {@code /sdcard/Android/data/<pkg>/}. Needs Shizuku, or it is skipped. */
    APP_PRIVATE
}
