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
        DriveTokens tokens = new DriveTokens(ctx);
        if (tokens.linked()) {
            File staging = new File(ctx.getExternalFilesDir(null), "staging");
            return new DriveSink(new DriveApi(tokens), staging, progress);
        }
        return new LocalFolderSink(localRoot().getAbsolutePath());
    }

    /** The active store, for callers that only read. */
    public static BackupSink active(Context ctx) throws IOException {
        return active(ctx, null);
    }
}
