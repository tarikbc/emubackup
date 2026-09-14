package com.tarikbc.emubackup;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256, lowercase hex.
 *
 * <p>Chosen over a faster non-cryptographic hash for one reason: {@code sha256sum -c} exists on
 * every machine, so the {@code SHA256SUMS} file emitted beside every backup can be verified by
 * hand with no copy of this app. That is the same no-lock-in principle that shapes the archive
 * layout. See {@code FORMAT.md} section 7.
 */
public final class Hashes {

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final int BUF = 64 * 1024;

    public static String sha256(InputStream in) throws IOException {
        MessageDigest md = newDigest();
        byte[] buf = new byte[BUF];
        int n;
        while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        return hex(md.digest());
    }

    public static String sha256(byte[] bytes) {
        return hex(newDigest().digest(bytes));
    }

    public static String hex(byte[] b) {
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            out[i * 2] = HEX[(b[i] >> 4) & 0xF];
            out[i * 2 + 1] = HEX[b[i] & 0xF];
        }
        return new String(out);
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Guaranteed present on every JVM and every Android release.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private Hashes() {}
}
