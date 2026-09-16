package com.tarikbc.emubackup;

import org.json.JSONObject;

/**
 * Turns whatever a person pastes from the Google Cloud console into a client id and secret.
 *
 * <p>The console offers the pair three ways: two values on screen, a copy button per value, and a
 * "Download JSON" button. People use all three, and the JSON is the one most likely to be pasted
 * whole into the first field it sees. Rejecting that as malformed would be technically correct
 * and actively unhelpful, so it is accepted.
 *
 * <p>Pure, so the parsing rules are tested without a device. See {@code test.sh}.
 */
public final class OAuthClientInput {

    /** A client id always ends this way; a wrong paste almost never does. */
    public static final String ID_SUFFIX = ".apps.googleusercontent.com";

    public final String id;
    public final String secret;

    /** Null when the input is usable, otherwise what to tell the person. */
    public final String error;

    private OAuthClientInput(String id, String secret, String error) {
        this.id = id;
        this.secret = secret;
        this.error = error;
    }

    public boolean ok() {
        return error == null;
    }

    private static OAuthClientInput bad(String why) {
        return new OAuthClientInput(null, null, why);
    }

    /**
     * @param idField     the client id, or the whole downloaded JSON.
     * @param secretField the client secret; ignored when the id field holds JSON.
     */
    public static OAuthClientInput parse(String idField, String secretField) {
        String a = idField == null ? "" : idField.trim();
        String b = secretField == null ? "" : secretField.trim();

        if (a.startsWith("{")) {
            OAuthClientInput fromJson = fromDownloadedJson(a);
            // Fall through to the plain reading only if this did not look like console JSON at
            // all; a JSON file that parsed but lacked the fields is an error worth reporting.
            if (fromJson != null) return fromJson;
        }

        if (a.isEmpty()) return bad("Paste the client ID from the Google Cloud console.");
        if (!a.endsWith(ID_SUFFIX)) {
            return bad("That does not look like a client ID. It should end in " + ID_SUFFIX);
        }
        if (b.isEmpty()) {
            return bad("Paste the client secret too. The console shows it once, in the dialog "
                    + "that appears when the client is created.");
        }
        // Whitespace inside either value is always a paste accident, and the resulting failure
        // from Google is an opaque invalid_client hours later.
        if (hasSpace(a) || hasSpace(b)) return bad("Remove the spaces or line breaks.");
        return new OAuthClientInput(a, b, null);
    }

    /** Null when the text is not the console's client JSON at all. */
    private static OAuthClientInput fromDownloadedJson(String text) {
        JSONObject root;
        try {
            root = new JSONObject(text);
        } catch (Exception e) {
            return null;
        }
        // The console nests under "installed" for a limited-input client and "web" for a web one.
        JSONObject o = root.optJSONObject("installed");
        if (o == null) o = root.optJSONObject("web");
        if (o == null) o = root;

        String id = o.optString("client_id", "").trim();
        String secret = o.optString("client_secret", "").trim();
        if (id.isEmpty() && secret.isEmpty()) return null;

        if (!id.endsWith(ID_SUFFIX)) {
            return bad("That JSON has no usable client_id.");
        }
        if (secret.isEmpty()) {
            return bad("That JSON has a client_id but no client_secret. Download it again from "
                    + "the client's own page, not from the consent screen.");
        }
        return new OAuthClientInput(id, secret, null);
    }

    private static boolean hasSpace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return true;
        }
        return false;
    }

    /** The middle of a client id, for showing which client is in use without showing the secret. */
    public static String shortId(String clientId) {
        if (clientId == null || clientId.isEmpty()) return "";
        String head = clientId.endsWith(ID_SUFFIX)
                ? clientId.substring(0, clientId.length() - ID_SUFFIX.length())
                : clientId;
        int dash = head.indexOf('-');
        return dash > 0 ? head.substring(0, dash) : head;
    }
}
