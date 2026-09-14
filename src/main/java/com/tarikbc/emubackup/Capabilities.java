package com.tarikbc.emubackup;

/**
 * What this device and install can currently do. Immutable snapshot, recomputed on resume.
 *
 * <p>Shizuku does not survive a reboot, and all-files access can be revoked in Settings, so
 * capabilities are never cached across a session. See {@code ARCHITECTURE.md} section 3.
 *
 * <p>Android-free by design; built by an Android-side probe and passed in.
 */
public final class Capabilities {

    /** {@code Environment.isExternalStorageManager()}. Gates every {@link Tier#SHARED} target. */
    public final boolean allFiles;

    /** Shizuku bound, permitted, and the capability probe passed. Gates {@link Tier#APP_PRIVATE}. */
    public final boolean appPrivate;

    /** An OAuth client is compiled in and a refresh token is held. */
    public final boolean drive;

    public Capabilities(boolean allFiles, boolean appPrivate, boolean drive) {
        this.allFiles = allFiles;
        this.appPrivate = appPrivate;
        this.drive = drive;
    }

    /** Nothing granted. The state on first launch. */
    public static Capabilities none() {
        return new Capabilities(false, false, false);
    }

    public boolean canRead(Tier tier) {
        return tier == Tier.SHARED ? allFiles : appPrivate;
    }

    public Capabilities withAppPrivate(boolean v) {
        return new Capabilities(allFiles, v, drive);
    }

    @Override public String toString() {
        return "allFiles=" + allFiles + " appPrivate=" + appPrivate + " drive=" + drive;
    }
}
