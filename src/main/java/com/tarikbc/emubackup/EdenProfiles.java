package com.tarikbc.emubackup;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Eden's {@code profiles.dat}: which username belongs to which save folder.
 *
 * <p>Layout, verified against the reference device's own file on 2026-09-12: 16 bytes of
 * padding, then 8 records of 0xC8 bytes: uuid (16), the uuid again (16), a timestamp (8), the
 * username (32, UTF-8, NUL padded) and 128 bytes of extra data. An all-zero uuid is an empty
 * slot. A profile's save folder is the uuid's 16 bytes <em>reversed</em>, as uppercase hex,
 * which is what the {@code {profile}} component of the eden-saves grouping captures.
 * Android-free; see {@code test.sh}.
 */
public final class EdenProfiles {

    private static final int HEADER = 16;
    private static final int RECORD = 0xC8;
    private static final int SLOTS = 8;

    private EdenProfiles() {}

    /** Save-folder name to username, for every filled slot the bytes hold. */
    public static Map<String, String> parse(byte[] b) {
        Map<String, String> out = new LinkedHashMap<>();
        if (b == null) return out;
        for (int i = 0; i < SLOTS; i++) {
            int at = HEADER + i * RECORD;
            if (at + RECORD > b.length) break;
            boolean empty = true;
            for (int k = 0; k < 16; k++) if (b[at + k] != 0) { empty = false; break; }
            if (empty) continue;
            StringBuilder folder = new StringBuilder(32);
            for (int k = 15; k >= 0; k--) folder.append(String.format(Locale.ROOT, "%02X", b[at + k] & 0xFF));
            int nameAt = at + 0x28, end = nameAt;
            while (end < nameAt + 32 && b[end] != 0) end++;
            String name = new String(b, nameAt, end - nameAt, StandardCharsets.UTF_8).trim();
            if (!name.isEmpty()) out.put(folder.toString(), name);
        }
        return out;
    }
}
