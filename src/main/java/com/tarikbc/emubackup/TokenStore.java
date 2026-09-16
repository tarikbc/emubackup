package com.tarikbc.emubackup;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Stores the Drive refresh token, encrypted with a key held in the framework keystore.
 *
 * <p>No {@code androidx.security} dependency: that would be another AAR to vendor for what the
 * framework does in about forty lines. The envelope format lives in {@link TokenEnvelope} so it is
 * unit-tested; only the cipher calls are here.
 *
 * <p>{@code android:allowBackup} is false partly because of this. The keystore key is not included
 * in a cloud backup, so a restored ciphertext would be undecryptable, and a stale token travelling
 * off the device is a liability rather than a convenience.
 */
public final class TokenStore {

    private static final String PREFS = "drive_tokens";
    private static final String KEY_ALIAS = "emubackup_drive_token";
    private static final String KEY_REFRESH = "refresh";
    private static final String KEY_ACCOUNT = "account_hint";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int TAG_BITS = 128;

    private final SharedPreferences prefs;

    public TokenStore(Context ctx) {
        this.prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean hasToken() {
        return prefs.contains(KEY_REFRESH);
    }

    public void save(String refreshToken) {
        try {
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] ct = c.doFinal(TokenEnvelope.utf8(refreshToken));
            String envelope = new TokenEnvelope(c.getIV(), ct).serialise();
            prefs.edit().putString(KEY_REFRESH, envelope).apply();
        } catch (Exception e) {
            throw new IllegalStateException("could not store the Drive token", e);
        }
    }

    /** The stored refresh token, or null when absent or no longer decryptable. */
    public String load() {
        String envelope = prefs.getString(KEY_REFRESH, null);
        if (envelope == null) return null;
        try {
            TokenEnvelope e = TokenEnvelope.parse(envelope);
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, e.iv));
            return TokenEnvelope.fromUtf8(c.doFinal(e.ciphertext));
        } catch (Exception ex) {
            // The key can disappear: a restored backup, a cleared keystore, a factory reset. The
            // token is then unrecoverable, so it is cleared and the user re-links rather than
            // seeing a permanent, unexplained failure.
            clear();
            return null;
        }
    }

    public void clear() {
        prefs.edit().remove(KEY_REFRESH).remove(KEY_ACCOUNT).apply();
    }

    public void setAccountHint(String hint) {
        prefs.edit().putString(KEY_ACCOUNT, hint).apply();
    }

    public String accountHint() {
        return prefs.getString(KEY_ACCOUNT, null);
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        KeyStore.Entry entry = ks.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Deliberately not requiring user authentication: scheduled backups run while the
                // device is locked, and a key that needs an unlock would break them silently.
                .setUserAuthenticationRequired(false)
                .build());
        return g.generateKey();
    }
}
