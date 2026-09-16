package com.tarikbc.emubackup;

import android.content.Context;
import java.io.IOException;

/**
 * Supplies access tokens to {@link DriveApi}, refreshing from the stored refresh token as needed.
 *
 * <p>The access token is held in memory only. It lives an hour and is cheap to obtain again, so
 * writing it to disk would add exposure for no benefit.
 */
public final class DriveTokens implements DriveApi.TokenSource {

    private final DeviceCodeAuth auth;
    private final TokenStore store;

    private String cached;
    private long cachedExpiresAtMs;

    public DriveTokens(Context ctx) {
        this.auth = new DeviceCodeAuth(OAuthConfig.CLIENT_ID, OAuthConfig.CLIENT_SECRET);
        this.store = new TokenStore(ctx);
    }

    public boolean linked() {
        return OAuthConfig.isConfigured() && store.hasToken();
    }

    public DeviceCodeAuth auth() {
        return auth;
    }

    public TokenStore store() {
        return store;
    }

    @Override public synchronized String accessToken() throws IOException {
        long now = System.currentTimeMillis();
        if (cached != null && now < cachedExpiresAtMs - 60_000) return cached;

        String refresh = store.load();
        if (refresh == null) throw new IOException("Google Drive is not linked");

        DeviceCodeAuth.Tokens t = auth.refresh(refresh, now);
        cached = t.accessToken;
        cachedExpiresAtMs = t.expiresAtMs;
        // Google returns a new refresh token only occasionally; persist it when it does.
        if (t.refreshToken != null && !t.refreshToken.equals(refresh)) store.save(t.refreshToken);
        return cached;
    }

    @Override public synchronized void invalidate() {
        cached = null;
        cachedExpiresAtMs = 0;
    }
}
