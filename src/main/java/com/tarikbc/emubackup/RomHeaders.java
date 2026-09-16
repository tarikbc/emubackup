package com.tarikbc.emubackup;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Reads a game's identifier from the first few kilobytes of its ROM.
 *
 * <p>Filenames carry the id for Switch and PSP dumps and almost never for 3DS, GameCube or
 * Wii, whose save folders are keyed by exactly that id. The id sits at a fixed offset in each
 * container's header, so it costs one small read per file and no decoding of content. Offsets
 * were checked against the reference device's own dumps. Android-free; see {@code test.sh}.
 *
 * <ul>
 *   <li>{@code .3ds/.cci}: NCSD magic at 0x100; the first NCCH starts at 0x4000 and holds
 *       the title id as 8 little-endian bytes at +0x108.</li>
 *   <li>{@code .rvz/.wia}: magic at 0; the disc header copy starts at 0x58 with the 6-char
 *       game id.</li>
 *   <li>{@code .iso/.gcm}: the game id at 0, confirmed by the Wii magic at 0x18 or the
 *       GameCube magic at 0x1C.</li>
 *   <li>{@code .wbfs}: magic at 0; the disc header at 0x200.</li>
 * </ul>
 */
public final class RomHeaders {

    private RomHeaders() {}

    public static Map<IdKind, String> read(File f) {
        Map<IdKind, String> out = new LinkedHashMap<>();
        String name = f.getName().toLowerCase(Locale.ROOT);
        try (RandomAccessFile in = new RandomAccessFile(f, "r")) {
            if (name.endsWith(".3ds") || name.endsWith(".cci")) {
                if (ascii(in, 0x100, 4).equals("NCSD") && ascii(in, 0x4000, 4).equals("NCCH")) {
                    long id = le64(in, 0x4108);
                    out.put(IdKind.N3DS_TITLE_ID, String.format(Locale.ROOT, "%016X", id));
                }
            } else if (name.endsWith(".rvz") || name.endsWith(".wia")) {
                String magic = ascii(in, 0, 3);
                if (magic.equals("RVZ") || magic.equals("WIA")) gameId(out, ascii(in, 0x58, 6));
            } else if (name.endsWith(".iso") || name.endsWith(".gcm")) {
                boolean wii = be32(in, 0x18) == 0x5D1C9EA3L;
                boolean gc = be32(in, 0x1C) == 0xC2339F3DL;
                if (wii || gc) gameId(out, ascii(in, 0, 6));
            } else if (name.endsWith(".wbfs")) {
                if (ascii(in, 0, 4).equals("WBFS")) gameId(out, ascii(in, 0x200, 6));
            }
        } catch (IOException | RuntimeException unreadable) {
            // A ROM that cannot be read stays nameless; the filename layer still applies.
        }
        return out;
    }

    private static void gameId(Map<IdKind, String> out, String id) {
        if (id.length() == 6 && id.matches("[A-Z0-9]{6}")) out.put(IdKind.GC_GAME_ID, id);
    }

    private static String ascii(RandomAccessFile in, long at, int n) throws IOException {
        if (in.length() < at + n) return "";
        byte[] b = new byte[n];
        in.seek(at);
        in.readFully(b);
        return new String(b, StandardCharsets.US_ASCII);
    }

    private static long le64(RandomAccessFile in, long at) throws IOException {
        byte[] b = new byte[8];
        in.seek(at);
        in.readFully(b);
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (b[i] & 0xFFL);
        return v;
    }

    private static long be32(RandomAccessFile in, long at) throws IOException {
        if (in.length() < at + 4) return -1;
        byte[] b = new byte[4];
        in.seek(at);
        in.readFully(b);
        return ((b[0] & 0xFFL) << 24) | ((b[1] & 0xFFL) << 16) | ((b[2] & 0xFFL) << 8) | (b[3] & 0xFFL);
    }
}
