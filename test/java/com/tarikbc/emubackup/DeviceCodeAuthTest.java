package com.tarikbc.emubackup;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DeviceCodeAuthTest {

    @Test void deviceCodeRequestCarriesOnlyWhatItNeeds() {
        String b = DeviceCodeAuth.buildDeviceCodeBody("abc.apps.googleusercontent.com");
        assertTrue(b.contains("client_id=abc.apps.googleusercontent.com"));
        assertTrue(b.contains("scope=" + DeviceCodeAuth.enc(DeviceCodeAuth.SCOPE)));
        assertFalse(b.contains("client_secret"), "the secret is not sent when starting the flow");
    }

    @Test void tokenRequestUsesTheDeviceGrant() {
        String b = DeviceCodeAuth.buildDeviceTokenBody("cid", "secret", "dev-code");
        assertTrue(b.contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code"));
        assertTrue(b.contains("device_code=dev-code"));
        assertTrue(b.contains("client_secret=secret"));
    }

    @Test void refreshRequestShape() {
        String b = DeviceCodeAuth.buildRefreshBody("cid", "secret", "refresh-me");
        assertTrue(b.contains("grant_type=refresh_token"));
        assertTrue(b.contains("refresh_token=refresh-me"));
    }

    @Test
    @DisplayName("a device code response yields what the screen has to show")
    void parseDeviceCode() throws Exception {
        String json = "{\"device_code\":\"D1\",\"user_code\":\"ABCD-EFGH\","
                + "\"verification_url\":\"https://www.google.com/device\","
                + "\"expires_in\":1800,\"interval\":5}";
        DeviceCodeAuth.Pending p = DeviceCodeAuth.parseDeviceCode(json, 1_000L);
        assertEquals("D1", p.deviceCode);
        assertEquals("ABCD-EFGH", p.userCode);
        assertEquals("https://www.google.com/device", p.verificationUrl);
        assertEquals(5, p.intervalSeconds);
        assertEquals(1_000L + 1_800_000L, p.expiresAtMs);
    }

    @Test
    @DisplayName("the waiting states are recognised, not treated as failures")
    void pollStates() {
        assertEquals(DeviceCodeAuth.PollResult.State.PENDING,
                DeviceCodeAuth.parsePoll("{\"error\":\"authorization_pending\"}", 0).state);
        assertEquals(DeviceCodeAuth.PollResult.State.SLOW_DOWN,
                DeviceCodeAuth.parsePoll("{\"error\":\"slow_down\"}", 0).state);
        assertEquals(DeviceCodeAuth.PollResult.State.DENIED,
                DeviceCodeAuth.parsePoll("{\"error\":\"access_denied\"}", 0).state);
        assertEquals(DeviceCodeAuth.PollResult.State.EXPIRED,
                DeviceCodeAuth.parsePoll("{\"error\":\"expired_token\"}", 0).state);
    }

    @Test void grantedPollReturnsUsableTokens() {
        String json = "{\"access_token\":\"AT\",\"refresh_token\":\"RT\",\"expires_in\":3600}";
        DeviceCodeAuth.PollResult r = DeviceCodeAuth.parsePoll(json, 10_000L);
        assertEquals(DeviceCodeAuth.PollResult.State.GRANTED, r.state);
        assertEquals("AT", r.tokens.accessToken);
        assertEquals("RT", r.tokens.refreshToken);
        assertEquals(10_000L + 3_600_000L, r.tokens.expiresAtMs);
    }

    @Test
    @DisplayName("a refresh response with no new refresh token keeps the old one")
    void refreshKeepsTheExistingRefreshToken() {
        // Google only returns a refresh token once. Dropping it here would silently unlink Drive
        // the first time an access token was renewed.
        DeviceCodeAuth.Tokens t = DeviceCodeAuth.parseTokens(
                "{\"access_token\":\"AT2\",\"expires_in\":3600}", "ORIGINAL", 0);
        assertNotNull(t);
        assertEquals("ORIGINAL", t.refreshToken);
    }

    @Test
    @DisplayName("a token is treated as expired slightly early")
    void expiryHasSlack() {
        DeviceCodeAuth.Tokens t = new DeviceCodeAuth.Tokens("AT", "RT", 100_000L);
        assertFalse(t.expired(0));
        assertTrue(t.expired(100_000L), "expired exactly at the deadline");
        assertTrue(t.expired(50_000L), "must expire early enough not to be used as it lapses");
    }

    @Test
    @DisplayName("invalid_grant is explained, because the real cause is a console setting")
    void invalidGrantNamesTheSevenDayTrap() {
        String msg = DeviceCodeAuth.describeRefreshFailure("{\"error\":\"invalid_grant\"}");
        assertTrue(msg.contains("Testing"), msg);
        assertTrue(msg.contains("7 days"), msg);
    }

    @Test void malformedResponsesFailCleanly() {
        assertEquals(DeviceCodeAuth.PollResult.State.FAILED,
                DeviceCodeAuth.parsePoll("not json", 0).state);
        assertNull(DeviceCodeAuth.parseTokens("{}", null, 0));
    }
}
