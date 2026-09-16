package com.tarikbc.emubackup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a person actually pastes, rather than what the field asks for.
 */
class OAuthClientInputTest {

    private static final String ID = "000000000000-0000000000000000000000000000000a"
            + ".apps.googleusercontent.com";
    private static final String SECRET = "GOCSPX-000000000000000000000000000";

    @Test void theOrdinaryCase() {
        OAuthClientInput in = OAuthClientInput.parse(ID, SECRET);
        assertTrue(in.ok(), in.error);
        assertEquals(ID, in.id);
        assertEquals(SECRET, in.secret);
    }

    @Test
    @DisplayName("surrounding whitespace is a paste artefact, not an error")
    void trimsEdges() {
        OAuthClientInput in = OAuthClientInput.parse("  " + ID + "\n", "\t" + SECRET + " ");
        assertTrue(in.ok(), in.error);
        assertEquals(ID, in.id);
        assertEquals(SECRET, in.secret);
    }

    @Test
    @DisplayName("whitespace inside a value is rejected rather than sent to Google")
    void rejectsInnerWhitespace() {
        // Google answers a mangled secret with invalid_client, hours later, on a refresh.
        OAuthClientInput in = OAuthClientInput.parse(ID, "GOCSPX-abc def");
        assertFalse(in.ok());
        assertTrue(in.error.contains("spaces"), in.error);
    }

    @Test
    @DisplayName("the console's downloaded JSON can be pasted whole into the first field")
    void acceptsDownloadedJson() {
        String json = "{\"installed\":{\"client_id\":\"" + ID + "\","
                + "\"project_id\":\"emubackup\","
                + "\"auth_uri\":\"https://accounts.google.com/o/oauth2/auth\","
                + "\"token_uri\":\"https://oauth2.googleapis.com/token\","
                + "\"client_secret\":\"" + SECRET + "\"}}";
        OAuthClientInput in = OAuthClientInput.parse(json, "");
        assertTrue(in.ok(), in.error);
        assertEquals(ID, in.id);
        assertEquals(SECRET, in.secret);
    }

    @Test
    @DisplayName("a web client's JSON nests under a different key and still works")
    void acceptsWebJson() {
        String json = "{\"web\":{\"client_id\":\"" + ID + "\",\"client_secret\":\"" + SECRET + "\"}}";
        OAuthClientInput in = OAuthClientInput.parse(json, "");
        assertTrue(in.ok(), in.error);
        assertEquals(SECRET, in.secret);
    }

    @Test
    @DisplayName("JSON with an id but no secret says which file to download instead")
    void jsonMissingSecret() {
        OAuthClientInput in = OAuthClientInput.parse("{\"installed\":{\"client_id\":\"" + ID + "\"}}", "");
        assertFalse(in.ok());
        assertTrue(in.error.contains("client_secret"), in.error);
    }

    @Test
    @DisplayName("something that is not a client id is caught before it reaches Google")
    void rejectsWrongPaste() {
        OAuthClientInput in = OAuthClientInput.parse("emubackup", SECRET);
        assertFalse(in.ok());
        assertTrue(in.error.contains(OAuthClientInput.ID_SUFFIX), in.error);
    }

    @Test void emptyAndSecretless() {
        assertFalse(OAuthClientInput.parse("", "").ok());
        assertNotNull(OAuthClientInput.parse("", "").error);
        assertFalse(OAuthClientInput.parse(null, null).ok());
        assertFalse(OAuthClientInput.parse(ID, "").ok());
    }

    @Test
    @DisplayName("the short id identifies a client without revealing the secret")
    void shortId() {
        assertEquals("000000000000", OAuthClientInput.shortId(ID));
        assertEquals("", OAuthClientInput.shortId(null));
        assertEquals("", OAuthClientInput.shortId(""));
    }
}
