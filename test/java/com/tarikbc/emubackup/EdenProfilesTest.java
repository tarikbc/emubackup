package com.tarikbc.emubackup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EdenProfilesTest {

    private static byte[] file(String[][] profiles) {
        byte[] b = new byte[1616];
        for (int i = 0; i < profiles.length; i++) {
            int at = 16 + i * 0xC8;
            byte[] uuid = hex(profiles[i][0]);
            System.arraycopy(uuid, 0, b, at, 16);
            System.arraycopy(uuid, 0, b, at + 16, 16);
            byte[] name = profiles[i][1].getBytes(StandardCharsets.UTF_8);
            System.arraycopy(name, 0, b, at + 0x28, name.length);
        }
        return b;
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    @Test
    @DisplayName("the save folder is the uuid reversed, and the name is the username")
    void folderIsReversedUuid() {
        Map<String, String> m = EdenProfiles.parse(file(new String[][] {
                { "00112233445566778899AABBCCDDEEFF", "Madu" },
                { "0F0E0D0C0B0A09080706050403020100", "Tarik" } }));
        assertEquals("Madu", m.get("FFEEDDCCBBAA99887766554433221100"));
        assertEquals("Tarik", m.get("000102030405060708090A0B0C0D0E0F"));
        assertEquals(2, m.size());
    }

    @Test void emptySlotsAndShortFilesAreTolerated() {
        byte[] b = file(new String[][] { { "00112233445566778899AABBCCDDEEFF", "Madu" } });
        assertEquals(1, EdenProfiles.parse(b).size());
        assertEquals(1, EdenProfiles.parse(Arrays.copyOf(b, 16 + 0xC8)).size());
        assertTrue(EdenProfiles.parse(Arrays.copyOf(b, 100)).isEmpty());
        assertTrue(EdenProfiles.parse(null).isEmpty());
    }
}
