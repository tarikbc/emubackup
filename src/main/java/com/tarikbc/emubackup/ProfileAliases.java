package com.tarikbc.emubackup;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * User-chosen names for emulator profiles, keyed by UUID.
 *
 * <p>The UUID is what actually finds the saves; the name is only for reading. Keeping the two
 * separate is why renaming a profile is safe, and why an unverified guess at a nickname parsed
 * out of an emulator's own binary format is never load-bearing.
 */
public final class ProfileAliases {

    private static final String PREFS = "profile_aliases";

    private final SharedPreferences prefs;

    public ProfileAliases(Context ctx) {
        this.prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The alias, or a shortened form of the UUID when none has been set. */
    public String nameFor(String uuid) {
        if (uuid == null || uuid.isEmpty()) return "";
        String v = prefs.getString(uuid.toUpperCase(java.util.Locale.ROOT), null);
        return v != null ? v : shorten(uuid);
    }

    public boolean hasAlias(String uuid) {
        return uuid != null && prefs.contains(uuid.toUpperCase(java.util.Locale.ROOT));
    }

    public void set(String uuid, String name) {
        String key = uuid.toUpperCase(java.util.Locale.ROOT);
        if (name == null || name.trim().isEmpty()) prefs.edit().remove(key).apply();
        else prefs.edit().putString(key, name.trim()).apply();
    }

    /** A 32-character hex UUID is unreadable in a list; the first eight are enough to tell apart. */
    static String shorten(String uuid) {
        return uuid.length() <= 10 ? uuid : uuid.substring(0, 8) + "…";
    }
}
