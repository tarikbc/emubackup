package com.tarikbc.emubackup;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.provider.Settings;

/**
 * All-files access, which every shared-storage target depends on.
 *
 * <p>Emulator save folders are owned by other apps at arbitrary paths under {@code /sdcard},
 * so scoped storage and {@code MediaStore} cannot reach them. {@code MANAGE_EXTERNAL_STORAGE}
 * is the only route, and it is granted through a Settings screen rather than a runtime dialog.
 *
 * <p>A missing grant must never silently no-op a tap; see {@code DESIGN.md} section 8.
 */
public final class Permissions {

    public static boolean hasAllFiles() {
        return Environment.isExternalStorageManager();
    }

    public static void requestAllFiles(Activity a) {
        try {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + a.getPackageName())));
        } catch (Exception e) {
            // Some OEM builds do not honour the per-package form. The list screen always exists.
            a.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    private Permissions() {}
}
