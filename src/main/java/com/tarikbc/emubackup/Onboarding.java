package com.tarikbc.emubackup;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Whether the first run has been walked through.
 *
 * <p>A flag rather than an inference. The obvious alternative, treating "a backup exists" as
 * proof, is wrong in both directions: someone can finish the walkthrough and decline to run a
 * backup, and someone restoring an install can have backups without ever having seen it.
 */
public final class Onboarding {

    private static final String PREFS = "onboarding";
    private static final String K_DONE = "done";

    private Onboarding() {}

    public static boolean isComplete(Context ctx) {
        return prefs(ctx).getBoolean(K_DONE, false);
    }

    public static void markComplete(Context ctx) {
        prefs(ctx).edit().putBoolean(K_DONE, true).apply();
    }

    /** Lets the walkthrough be seen again from the hub, and makes it testable on a real device. */
    public static void reset(Context ctx) {
        prefs(ctx).edit().remove(K_DONE).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
