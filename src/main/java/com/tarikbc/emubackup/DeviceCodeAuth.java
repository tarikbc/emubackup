package com.tarikbc.emubackup;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * OAuth 2.0 device authorization grant: the app shows a code, the user types it on another
 * machine.
 *
 * <p>Chosen over an in-app browser flow because Google's Android client type binds to the package
 * name plus the SHA-1 of the signing certificate, and this project's build generates a throwaway
 * debug keystore per clone — every contributor and CI would need their fingerprint registered.
 * The device flow needs no redirect URI, no custom scheme and no fingerprint, so one client id
 * works everywhere. On a handheld with no keyboard, typing a short code elsewhere is also the
 * easier path.
 *
 * <p>The client secret issued for this client type is embedded in the app. That is public by
 * design for a device-flow client; security rests on the user-granted authorization, not on the
 * secret. See {@code docs/DRIVE_SETUP.md}.
 *
 * <p>Android-free by design; see {@code test.sh}.
 */
public final class DeviceCodeAuth {

    public static final String SCOPE = "https://www.googleapis.com/auth/drive.file";
    private static final String DEVICE_ENDPOINT = "https://oauth2.googleapis.com/device/code";
    private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    private static final String GRANT_DEVICE = "urn:ietf:params:oauth:grant-type:device_code";

    /** What to show the user while they authorise on another device. */
    public static final class Pending {
        public final String deviceCode, userCode, verificationUrl;
        public final long expiresAtMs;
        public final int intervalSeconds;

        Pending(String deviceCode, String userCode, String verificationUrl,
                long expiresAtMs, int intervalSeconds) {
            this.deviceCode = deviceCode;
            this.userCode = userCode;
            this.verificationUrl = verificationUrl;
            this.expiresAtMs = expiresAtMs;
            this.intervalSeconds = intervalSeconds;
        }
    }

    public static final class Tokens {
        public final String accessToken, refreshToken;
        public final long expiresAtMs;

        public Tokens(String accessToken, String refreshToken, long expiresAtMs) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.expiresAtMs = expiresAtMs;
        }

        public boolean expired(long nowMs) {
            // A minute of slack, so a token is never used in the moment it lapses.
            return nowMs >= expiresAtMs - 60_000;
        }
    }

    /** The poll is still waiting, or the user declined, or it timed out. */
    public static final class PollResult {
        public enum State { PENDING, SLOW_DOWN, GRANTED, DENIED, EXPIRED, FAILED }
        public final State state;
        public final Tokens tokens;
        public final String detail;

        PollResult(State state, Tokens tokens, String detail) {
            this.state = state;
            this.tokens = tokens;
            this.detail = detail;
        }
    }

    private final String clientId, clientSecret;

    public DeviceCodeAuth(String clientId, String clientSecret) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public boolean configured() {
        return clientId != null && !clientId.isEmpty();
    }

    /** Starts the flow. Blocking. */
    public Pending begin(long nowMs) throws IOException {
        String body = buildDeviceCodeBody(clientId);
        String response = form(DEVICE_ENDPOINT, body);
        return parseDeviceCode(response, nowMs);
    }

    /** One poll. Blocking. Callers wait {@link Pending#intervalSeconds} between calls. */
    public PollResult poll(String deviceCode, long nowMs) throws IOException {
        String body = buildDeviceTokenBody(clientId, clientSecret, deviceCode);
        String response;
        try {
            response = form(TOKEN_ENDPOINT, body);
        } catch (HttpError e) {
            // The pending and slow-down states arrive as 4xx with a JSON error body, so they must
            // be read rather than treated as failures.
            return parsePoll(e.body, nowMs);
        }
        return parsePoll(response, nowMs);
    }

    /** Exchanges a refresh token for a fresh access token. Blocking. */
    public Tokens refresh(String refreshToken, long nowMs) throws IOException {
        String response;
        try {
            response = form(TOKEN_ENDPOINT, buildRefreshBody(clientId, clientSecret, refreshToken));
        } catch (HttpError e) {
            throw new IOException(describeRefreshFailure(e.body), e);
        }
        Tokens t = parseTokens(response, refreshToken, nowMs);
        if (t == null) throw new IOException("Drive returned no access token");
        return t;
    }

    // ------------------------------------------------------------- pure builders

    static String buildDeviceCodeBody(String clientId) {
        return "client_id=" + enc(clientId) + "&scope=" + enc(SCOPE);
    }

    static String buildDeviceTokenBody(String clientId, String clientSecret, String deviceCode) {
        StringBuilder b = new StringBuilder();
        b.append("client_id=").append(enc(clientId));
        if (clientSecret != null && !clientSecret.isEmpty()) {
            b.append("&client_secret=").append(enc(clientSecret));
        }
        b.append("&device_code=").append(enc(deviceCode));
        b.append("&grant_type=").append(enc(GRANT_DEVICE));
        return b.toString();
    }

    static String buildRefreshBody(String clientId, String clientSecret, String refreshToken) {
        StringBuilder b = new StringBuilder();
        b.append("client_id=").append(enc(clientId));
        if (clientSecret != null && !clientSecret.isEmpty()) {
            b.append("&client_secret=").append(enc(clientSecret));
        }
        b.append("&refresh_token=").append(enc(refreshToken));
        b.append("&grant_type=refresh_token");
        return b.toString();
    }

    static Pending parseDeviceCode(String json, long nowMs) throws IOException {
        try {
            JSONObject o = new JSONObject(json);
            String device = o.optString("device_code", null);
            String user = o.optString("user_code", null);
            if (device == null || user == null) {
                throw new IOException("device code response was missing a code: " + json);
            }
            String url = o.optString("verification_url", o.optString("verification_uri",
                    "https://www.google.com/device"));
            int interval = Math.max(1, o.optInt("interval", 5));
            long expires = nowMs + Math.max(60, o.optInt("expires_in", 900)) * 1000L;
            return new Pending(device, user, url, expires, interval);
        } catch (JSONException e) {
            throw new IOException("unparseable device code response", e);
        }
    }

    static PollResult parsePoll(String json, long nowMs) {
        JSONObject o;
        try {
            o = new JSONObject(json);
        } catch (JSONException e) {
            return new PollResult(PollResult.State.FAILED, null, "unparseable response");
        }
        String error = o.optString("error", null);
        if (error != null && !error.isEmpty()) {
            switch (error) {
                case "authorization_pending":
                    return new PollResult(PollResult.State.PENDING, null, null);
                case "slow_down":
                    return new PollResult(PollResult.State.SLOW_DOWN, null, null);
                case "access_denied":
                    return new PollResult(PollResult.State.DENIED, null, "You declined the request");
                case "expired_token":
                    return new PollResult(PollResult.State.EXPIRED, null, "The code expired");
                default:
                    return new PollResult(PollResult.State.FAILED, null,
                            o.optString("error_description", error));
            }
        }
        Tokens t = parseTokens(json, null, nowMs);
        return t == null
                ? new PollResult(PollResult.State.FAILED, null, "no access token in response")
                : new PollResult(PollResult.State.GRANTED, t, null);
    }

    static Tokens parseTokens(String json, String fallbackRefresh, long nowMs) {
        try {
            JSONObject o = new JSONObject(json);
            String access = o.optString("access_token", null);
            if (access == null || access.isEmpty()) return null;
            String refresh = o.optString("refresh_token", null);
            if (refresh == null || refresh.isEmpty()) refresh = fallbackRefresh;
            long expires = nowMs + Math.max(60, o.optLong("expires_in", 3600)) * 1000L;
            return new Tokens(access, refresh, expires);
        } catch (JSONException e) {
            return null;
        }
    }

    /**
     * Turns {@code invalid_grant} into the explanation that actually helps.
     *
     * <p>A consent screen left in Testing revokes refresh tokens after seven days, which breaks
     * scheduled backups weekly and looks like a random authentication bug. Naming it here saves
     * the user a long evening.
     */
    static String describeRefreshFailure(String body) {
        if (body != null && body.contains("invalid_grant")) {
            return "Drive access expired. If your Google Cloud consent screen is still in "
                    + "\"Testing\", switch it to \"In production\" — Testing revokes access every "
                    + "7 days. Then link Drive again.";
        }
        return "Could not refresh Drive access: " + (body == null ? "" : body);
    }

    // ------------------------------------------------------------------ plumbing

    /** Carries the body, because OAuth puts its meaningful errors in a 4xx response. */
    static final class HttpError extends IOException {
        final int code;
        final String body;
        HttpError(int code, String body) {
            super("HTTP " + code);
            this.code = code;
            this.body = body;
        }
    }

    private static String form(String url, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(30_000);
        c.setReadTimeout(30_000);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        c.setDoOutput(true);
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(payload.length);
        try (OutputStream o = c.getOutputStream()) {
            o.write(payload);
        }
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new HttpError(code, DriveApi.readAll(c.getErrorStream()));
        }
        String out = DriveApi.readAll(c.getInputStream());
        c.disconnect();
        return out;
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
