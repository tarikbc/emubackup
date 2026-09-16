package com.tarikbc.emubackup;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.net.Uri;

/**
 * Where the person has asked their backups to go.
 *
 * <p>Three destinations, in descending order of how much they protect against: Google Drive,
 * which survives the device being lost; a folder they pick, which reaches an SD card, a USB
 * drive, or a sync app's own folder and needs no account at all; and a folder on this device,
 * which is honest about being the weakest and is still better than nothing.
 *
 * <p>A stored choice can stop being possible between one backup and the next: an account is
 * unlinked, a card is removed, a permission is withdrawn on reinstall. Every read is therefore
 * validated rather than trusted, and falls back to the device folder, because a backup that
 * lands somewhere less useful beats one that does not happen.
 */
public final class Destination {

    public enum Kind { DEVICE, FOLDER, DRIVE }

    private static final String PREFS = "destination";
    private static final String K_KIND = "kind";
    private static final String K_TREE = "tree_uri";
    private static final String K_LABEL = "tree_label";

    private Destination() {}

    /**
     * The chosen destination, reduced to one that currently works.
     *
     * <p>With nothing chosen, a linked Drive account wins. That is what the app did before there
     * was a choice to store, and silently demoting an existing install to a local folder on
     * upgrade would be a data-loss-shaped surprise.
     */
    public static Kind effective(Context ctx) {
        String stored = prefs(ctx).getString(K_KIND, null);
        if (stored == null) return new DriveTokens(ctx).linked() ? Kind.DRIVE : Kind.DEVICE;

        Kind k;
        try {
            k = Kind.valueOf(stored);
        } catch (IllegalArgumentException e) {
            return Kind.DEVICE;
        }
        if (k == Kind.DRIVE && !new DriveTokens(ctx).linked()) return Kind.DEVICE;
        if (k == Kind.FOLDER && folderUri(ctx) == null) return Kind.DEVICE;
        return k;
    }

    /** True when a choice was stored but cannot be honoured, which the UI should say out loud. */
    public static boolean chosenButUnavailable(Context ctx) {
        String stored = prefs(ctx).getString(K_KIND, null);
        if (stored == null) return false;
        return !stored.equals(effective(ctx).name());
    }

    public static void chooseDevice(Context ctx) {
        prefs(ctx).edit().putString(K_KIND, Kind.DEVICE.name()).apply();
    }

    public static void chooseDrive(Context ctx) {
        prefs(ctx).edit().putString(K_KIND, Kind.DRIVE.name()).apply();
    }

    /**
     * Records a folder picked through {@code ACTION_OPEN_DOCUMENT_TREE}.
     *
     * <p>The permission must be taken persistably here. Without that call the grant dies with the
     * process, and the first scheduled backup after a reboot would fail with a SecurityException
     * that looks like a bug rather than a missing flag.
     */
    public static void chooseFolder(Context ctx, Uri tree, String label) {
        ctx.getContentResolver().takePersistableUriPermission(tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        prefs(ctx).edit()
                .putString(K_KIND, Kind.FOLDER.name())
                .putString(K_TREE, tree.toString())
                .putString(K_LABEL, label == null ? tree.toString() : label)
                .apply();
    }

    /** The picked folder, or null when none is held or the grant is gone. */
    public static Uri folderUri(Context ctx) {
        String s = prefs(ctx).getString(K_TREE, null);
        if (s == null) return null;
        Uri uri = Uri.parse(s);
        for (UriPermission p : ctx.getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(uri) && p.isReadPermission() && p.isWritePermission()) return uri;
        }
        // The grant is gone: revoked in Settings, or the volume was removed. Reporting null lets
        // the caller fall back and lets the UI offer to pick the folder again.
        return null;
    }

    public static String folderLabel(Context ctx) {
        return prefs(ctx).getString(K_LABEL, "a folder you picked");
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
