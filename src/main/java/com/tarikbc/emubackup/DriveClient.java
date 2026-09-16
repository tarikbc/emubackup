package com.tarikbc.emubackup;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The Google OAuth client this install uses, from the build or from the person using it.
 *
 * <p>Released APKs carry no client, deliberately: a published APK cannot hide an OAuth secret, so
 * shipping one would route every user through a single Cloud project. That left Drive reachable
 * only by cloning the repository and rebuilding, which is a developer's answer to a save-backup
 * app. A client entered here needs a browser and about ten minutes, and no toolchain at all.
 *
 * <p>These two values are not treated as secret, because for this client type they are not. Google
 * classifies an installed-app secret as non-confidential; security rests on the user-granted
 * authorization, which lives in {@link TokenStore} and is encrypted. Keeping them in plain
 * preferences states that honestly rather than implying a protection that is not there.
 */
public final class DriveClient {

    private static final String PREFS = "drive_client";
    private static final String K_ID = "client_id";
    private static final String K_SECRET = "client_secret";

    public final String id;
    public final String secret;

    /** True when the values came from the build rather than from the person. */
    public final boolean fromBuild;

    private DriveClient(String id, String secret, boolean fromBuild) {
        this.id = id;
        this.secret = secret;
        this.fromBuild = fromBuild;
    }

    /**
     * The client in force. A build-time client wins, so a developer build behaves predictably and
     * cannot be silently redirected by a stale entry in preferences.
     */
    public static DriveClient of(Context ctx) {
        if (OAuthConfig.isConfigured()) {
            return new DriveClient(OAuthConfig.CLIENT_ID, OAuthConfig.CLIENT_SECRET, true);
        }
        SharedPreferences p = prefs(ctx);
        return new DriveClient(p.getString(K_ID, ""), p.getString(K_SECRET, ""), false);
    }

    public boolean configured() {
        return id != null && !id.isEmpty() && secret != null && !secret.isEmpty();
    }

    /**
     * Stores a client, dropping the existing authorization only if it belonged to another one.
     */
    public static void save(Context ctx, String id, String secret) {
        prefs(ctx).edit().putString(K_ID, id).putString(K_SECRET, secret).apply();
        TokenStore tokens = new TokenStore(ctx);
        // Correcting a typo in the secret should not log you out. Only a genuinely different
        // client makes the stored token worthless. An unknown owner is treated as different,
        // because a dead token surfaces later as invalid_grant, which reads like an expired
        // login and sends the person to the wrong place.
        if (!id.equals(tokens.clientId())) tokens.clear();
    }

    public static void clear(Context ctx) {
        prefs(ctx).edit().remove(K_ID).remove(K_SECRET).apply();
        new TokenStore(ctx).clear();
    }

    /** True when the person, not the build, supplied a client that can be replaced or removed. */
    public static boolean isUserSupplied(Context ctx) {
        return !OAuthConfig.isConfigured() && !prefs(ctx).getString(K_ID, "").isEmpty();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
