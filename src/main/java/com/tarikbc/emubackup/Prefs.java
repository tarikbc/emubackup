package com.tarikbc.emubackup;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Moves {@link Settings} and {@link RunLog} to and from storage, and nothing else.
 *
 * <p>Every rule about what a setting may be lives in {@code Settings}, where it is tested. This
 * class is deliberately dull so that reading it tells you the whole story of where the values go.
 */
public final class Prefs {

    private static final String PREFS = "settings";
    private static final String K_FREQ = "frequency";
    private static final String K_CHARGING = "requires_charging";
    private static final String K_UNMETERED = "requires_unmetered";
    private static final String K_KEEP = "keep_versions";
    private static final String K_BUDGET = "budget_bytes";
    private static final String K_LOG = "run_log";

    private Prefs() {}

    public static Settings settings(Context ctx) {
        SharedPreferences p = prefs(ctx);
        Settings d = Settings.defaults();
        return new Settings(
                Settings.Frequency.parse(p.getString(K_FREQ, d.frequency.name())),
                p.getBoolean(K_CHARGING, d.requiresCharging),
                p.getBoolean(K_UNMETERED, d.requiresUnmetered),
                p.getInt(K_KEEP, d.keepVersions),
                p.getLong(K_BUDGET, d.budgetBytes));
    }

    /** Stores the settings and brings the scheduled job into line with them. */
    public static void save(Context ctx, Settings s) {
        prefs(ctx).edit()
                .putString(K_FREQ, s.frequency.name())
                .putBoolean(K_CHARGING, s.requiresCharging)
                .putBoolean(K_UNMETERED, s.requiresUnmetered)
                .putInt(K_KEEP, s.keepVersions)
                .putLong(K_BUDGET, s.budgetBytes)
                .apply();
        BackupJobScheduler.apply(ctx, s);
    }

    public static RunLog log(Context ctx) {
        return RunLog.fromJson(prefs(ctx).getString(K_LOG, null));
    }

    /** Appends a run and returns the log it produced, so the caller can act on the new state. */
    public static RunLog record(Context ctx, RunLog.Run run) {
        RunLog updated = log(ctx).with(run);
        prefs(ctx).edit().putString(K_LOG, updated.toJson()).apply();
        return updated;
    }

    public static void clearLog(Context ctx) {
        prefs(ctx).edit().remove(K_LOG).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
