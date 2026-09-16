package com.tarikbc.emubackup;

import android.content.Context;
import android.os.Environment;
import java.io.File;
import java.io.IOException;

/**
 * Resolves the one store this device writes backups to, and reads them back from.
 *
 * <p>It exists because those two decisions used to live in different places. {@code
 * BackupService} chose Drive whenever an account was linked, while {@code RestoreSession}
 * always opened the local folder. The result was a store that could be written but never read:
 * a Drive backup did not appear on the Backups screen, could not be previewed, and could not be
 * restored from inside the app, even though {@link DriveSink} implements every read method the
 * interface has. Nothing failed loudly; the screen was simply empty.
 *
 * <p>One resolver is the fix. A store that can be selected here is necessarily a store the whole
 * app agrees on, so a future destination cannot be half-wired the same way.
 */
public final class Stores {

    private Stores() {}

    /** Where a local-folder store lives. Deliberately in shared storage, not app-private. */
    public static File localRoot() {
        return new File(Environment.getExternalStorageDirectory(), "EmuBackup");
    }

    /**
     * The active store.
     *
     * <p>The local folder is never merely a fallback: it is the guarantee that a backup outlives
     * this app and any account, and it is what the whole test suite exercises.
     *
     * @param progress upload progress, or null when nothing is being uploaded, such as when the
     *                 caller only wants to list versions or read a manifest.
     */
    public static BackupSink active(Context ctx, DriveApi.ProgressListener progress)
            throws IOException {
        File staging = new File(ctx.getExternalFilesDir(null), "staging");
        switch (Destination.effective(ctx)) {
            case DRIVE:
                return new DriveSink(new DriveApi(new DriveTokens(ctx)), staging, progress);
            case FOLDER:
                return new SafFolderSink(ctx, Destination.folderUri(ctx),
                        Destination.folderLabel(ctx), staging);
            case DEVICE:
            default:
                return new LocalFolderSink(localRoot().getAbsolutePath());
        }
    }

    /**
     * A short stable name for the active store, for anything cached per store.
     *
     * <p>Version ids are per-store counters, so {@code v0003} in Drive and {@code v0003} in a
     * picked folder are unrelated. Anything keyed by version id alone would mix them.
     */
    public static String key(Context ctx) {
        switch (Destination.effective(ctx)) {
            case DRIVE: return "drive";
            case FOLDER: return "folder-" + Integer.toHexString(
                    String.valueOf(Destination.folderUri(ctx)).hashCode());
            default: return "device";
        }
    }

    /** The active store, for callers that only read. */
    public static BackupSink active(Context ctx) throws IOException {
        return active(ctx, null);
    }
}
