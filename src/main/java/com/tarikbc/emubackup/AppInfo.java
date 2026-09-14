package com.tarikbc.emubackup;

import android.content.Context;
import android.content.pm.PackageManager;

/**
 * Package presence and versions, backed by {@link PackageManager}.
 *
 * <p>The version of the emulator that produced a save is recorded in every manifest, because
 * "which build wrote this" is the first question when a restored save will not load.
 */
public final class AppInfo implements PackagePresence, EmulatorVersions {

    private final PackageManager pm;

    public AppInfo(Context ctx) {
        this.pm = ctx.getPackageManager();
    }

    @Override public boolean isInstalled(String pkg) {
        try {
            pm.getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Null when the package is absent. */
    @Override public String versionName(String pkg) {
        try {
            return pm.getPackageInfo(pkg, 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    @Override public long versionCode(String pkg) {
        try {
            return pm.getPackageInfo(pkg, 0).getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    /** The first installed package from an emulator's candidate list, or null. */
    public String firstInstalled(Emulator e) {
        for (String p : e.packages) if (isInstalled(p)) return p;
        return null;
    }
}
