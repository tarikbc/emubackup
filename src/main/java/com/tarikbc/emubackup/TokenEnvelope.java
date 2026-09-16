package com.tarikbc.emubackup;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * The on-disk format for an encrypted refresh token: {@code v1:<base64 iv>:<base64 ciphertext>}.
 *
 * <p>Split from the keystore code so the format itself is unit-tested, leaving only the cipher
 * calls on the Android side. An unrecognised version prefix is refused rather than guessed at,
 * because a misread token silently becomes a broken Drive link.
 *
 * <p>Android-free by design; see {@code test.sh}.
 */
public final class TokenEnvelope {

    private static final String VERSION = "v1";

    public final byte[] iv;
    public final byte[] ciphertext;

    public TokenEnvelope(byte[] iv, byte[] ciphertext) {
        if (iv == null || iv.length == 0) throw new IllegalArgumentException("iv required");
        if (ciphertext == null || ciphertext.length == 0) throw new IllegalArgumentException("ciphertext required");
        this.iv = iv;
        this.ciphertext = ciphertext;
    }

    public String serialise() {
        Base64.Encoder e = Base64.getEncoder();
        return VERSION + ":" + e.encodeToString(iv) + ":" + e.encodeToString(ciphertext);
    }

    public static TokenEnvelope parse(String s) {
        if (s == null || s.isEmpty()) throw new IllegalArgumentException("empty envelope");
        String[] parts = s.split(":", 3);
        if (parts.length != 3) throw new IllegalArgumentException("malformed envelope");
        if (!VERSION.equals(parts[0])) {
            throw new IllegalArgumentException("unsupported envelope version: " + parts[0]);
        }
        try {
            Base64.Decoder d = Base64.getDecoder();
            return new TokenEnvelope(d.decode(parts[1]), d.decode(parts[2]));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("envelope is not valid base64", e);
        }
    }

    public static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    public static String fromUtf8(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
