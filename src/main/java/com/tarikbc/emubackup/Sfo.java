package com.tarikbc.emubackup;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sony's PARAM.SFO: the little table of strings a PSP save carries about itself, including
 * the game's title. Reading it names a save without needing the disc at all.
 *
 * <p>Layout: magic {@code \0PSF}, version, key table offset, data table offset, entry count,
 * then 16-byte index entries (key offset u16, format u16, length u32, max length u32, data
 * offset u32). Only UTF-8 string entries (format 0x0204) are returned. Android-free.
 */
public final class Sfo {

    private Sfo() {}

    public static Map<String, String> parse(byte[] b) {
        Map<String, String> out = new LinkedHashMap<>();
        if (b == null || b.length < 20 || b[0] != 0 || b[1] != 'P' || b[2] != 'S' || b[3] != 'F') return out;
        int keys = le32(b, 8), data = le32(b, 12), count = le32(b, 16);
        for (int i = 0; i < count && i < 256; i++) {
            int e = 20 + i * 16;
            if (e + 16 > b.length) break;
            int keyOff = le16(b, e), fmt = le16(b, e + 2), len = le32(b, e + 4), dataOff = le32(b, e + 12);
            if (fmt != 0x0204) continue;
            String key = cstring(b, keys + keyOff, 64);
            int start = data + dataOff;
            if (start < 0 || start >= b.length || len < 0) continue;
            String value = cstring(b, start, Math.min(len, b.length - start));
            if (key != null) out.put(key, value);
        }
        return out;
    }

    private static String cstring(byte[] b, int at, int max) {
        if (at < 0 || at >= b.length) return null;
        int end = at;
        while (end < b.length && end - at < max && b[end] != 0) end++;
        return new String(b, at, end - at, StandardCharsets.UTF_8).trim();
    }

    private static int le16(byte[] b, int at) {
        return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] b, int at) {
        return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8) | ((b[at + 2] & 0xFF) << 16) | ((b[at + 3] & 0xFF) << 24);
    }
}
