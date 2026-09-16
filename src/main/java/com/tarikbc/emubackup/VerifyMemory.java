package com.tarikbc.emubackup;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

/**
 * What the last check of each backup found, so the Backups list can say "checked 2 days ago"
 * instead of making someone run it again to know.
 *
 * <p>Keyed by store and version id, because version ids are per-store counters. Device-local:
 * a check proves the copy this device read, and another device reads its own.
 */
public final class VerifyMemory {

    public static final class Entry {
        public final long atMs;
        public final boolean ok;
        public final String summary;

        Entry(long atMs, boolean ok, String summary) {
            this.atMs = atMs;
            this.ok = ok;
            this.summary = summary;
        }
    }

    private VerifyMemory() {}

    public static void record(Context ctx, String versionId, boolean ok, String summary) {
        try {
            JSONObject o = new JSONObject();
            o.put("at", System.currentTimeMillis());
            o.put("ok", ok);
            o.put("summary", summary == null ? "" : summary);
            prefs(ctx).edit().putString(key(ctx, versionId), o.toString()).apply();
        } catch (Exception ignored) {
            // A forgotten check costs a re-check, nothing else.
        }
    }

    /** Null when this backup has not been checked from this device. */
    public static Entry get(Context ctx, String versionId) {
        String s = prefs(ctx).getString(key(ctx, versionId), null);
        if (s == null) return null;
        try {
            JSONObject o = new JSONObject(s);
            return new Entry(o.getLong("at"), o.getBoolean("ok"), o.optString("summary", ""));
        } catch (Exception e) {
            return null;
        }
    }

    private static String key(Context ctx, String versionId) {
        return Stores.key(ctx) + "/" + versionId;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences("verify", Context.MODE_PRIVATE);
    }
}
