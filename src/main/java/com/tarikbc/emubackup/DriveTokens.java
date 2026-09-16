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
    private final String clientId;
    private final boolean haveClient;

    private String cached;
    private long cachedExpiresAtMs;

    public DriveTokens(Context ctx) {
        DriveClient client = DriveClient.of(ctx);
        this.auth = new DeviceCodeAuth(client.id, client.secret);
        this.store = new TokenStore(ctx);
        this.clientId = client.id;
        this.haveClient = client.configured();
    }

    public boolean linked() {
        return haveClient && store.hasToken();
    }

    public DeviceCodeAuth auth() {
        return auth;
    }

    public TokenStore store() {
        return store;
    }

    /** Stores a newly granted refresh token together with the client that obtained it. */
    public void saveRefreshToken(String refreshToken) {
        store.save(refreshToken);
        store.setClientId(clientId);
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
        if (t.refreshToken != null && !t.refreshToken.equals(refresh)) saveRefreshToken(t.refreshToken);
        return cached;
    }

    @Override public synchronized void invalidate() {
        cached = null;
        cachedExpiresAtMs = 0;
    }
}
