package com.tarikbc.emubackup;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TokenEnvelopeTest {

    @Test void roundTrip() {
        byte[] iv = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
        byte[] ct = TokenEnvelope.utf8("a refresh token");
        TokenEnvelope back = TokenEnvelope.parse(new TokenEnvelope(iv, ct).serialise());
        assertArrayEquals(iv, back.iv);
        assertEquals("a refresh token", TokenEnvelope.fromUtf8(back.ciphertext));
    }

    @Test
    @DisplayName("an unknown version is refused rather than guessed at")
    void unknownVersionRejected() {
        // Misreading a stored token silently produces a broken Drive link rather than an error.
        assertThrows(IllegalArgumentException.class, () -> TokenEnvelope.parse("v2:AAAA:BBBB"));
    }

    @Test void malformedInputRejected() {
        assertThrows(IllegalArgumentException.class, () -> TokenEnvelope.parse(""));
        assertThrows(IllegalArgumentException.class, () -> TokenEnvelope.parse("v1:onlytwo"));
        assertThrows(IllegalArgumentException.class, () -> TokenEnvelope.parse("v1:!!!:!!!"));
        assertThrows(IllegalArgumentException.class,
                () -> new TokenEnvelope(new byte[0], new byte[]{1}));
    }
}
