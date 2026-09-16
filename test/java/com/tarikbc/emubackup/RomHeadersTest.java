package com.tarikbc.emubackup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Synthetic headers laid out exactly as the reference device's own dumps were measured. */
class RomHeadersTest {

    private static void put(RandomAccessFile f, long at, byte[] b) throws IOException {
        f.seek(at);
        f.write(b);
    }

    private static byte[] s(String v) {
        return v.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    @DisplayName("a .3ds carries its title id in the first NCCH")
    void threeDs(@TempDir Path tmp) throws Exception {
        Path p = tmp.resolve("Animal Crossing - New Leaf (USA).3ds");
        try (RandomAccessFile f = new RandomAccessFile(p.toFile(), "rw")) {
            f.setLength(0x5000);
            put(f, 0x100, s("NCSD"));
            put(f, 0x120, new byte[] { 0x20, 0, 0, 0 });   // partition 0 at 0x20 * 0x200 = 0x4000
            put(f, 0x4100, s("NCCH"));
            // 0004000000086300, little-endian
            put(f, 0x4108, new byte[] { 0x00, 0x63, 0x08, 0x00, 0x00, 0x00, 0x04, 0x00 });
        }
        assertEquals("0004000000086300", RomHeaders.read(p.toFile()).get(IdKind.N3DS_TITLE_ID));

        // A dump whose first partition sits elsewhere is found through the table.
        Path q = tmp.resolve("Mario Kart 7 (Europe).3ds");
        try (RandomAccessFile f = new RandomAccessFile(q.toFile(), "rw")) {
            f.setLength(0x9000);
            put(f, 0x100, s("NCSD"));
            put(f, 0x120, new byte[] { 0x40, 0, 0, 0 });   // 0x8000
            put(f, 0x8100, s("NCCH"));
            put(f, 0x8108, new byte[] { 0x00, 0x1c, 0x03, 0x00, 0x00, 0x00, 0x04, 0x00 });
        }
        assertEquals("0004000000031C00", RomHeaders.read(q.toFile()).get(IdKind.N3DS_TITLE_ID));
    }

    @Test
    @DisplayName("an RVZ carries the disc header at 0x58")
    void rvz(@TempDir Path tmp) throws Exception {
        Path p = tmp.resolve("Mario Kart Wii (USA).rvz");
        try (RandomAccessFile f = new RandomAccessFile(p.toFile(), "rw")) {
            f.setLength(0x100);
            put(f, 0, new byte[] { 'R', 'V', 'Z', 1 });
            put(f, 0x58, s("RMCE01"));
        }
        assertEquals("RMCE01", RomHeaders.read(p.toFile()).get(IdKind.GC_GAME_ID));
    }

    @Test
    @DisplayName("a Wii ISO is recognised by its magic, a random .iso is not")
    void iso(@TempDir Path tmp) throws Exception {
        Path p = tmp.resolve("MarioKart.iso");
        try (RandomAccessFile f = new RandomAccessFile(p.toFile(), "rw")) {
            f.setLength(0x100);
            put(f, 0, s("RMCP01"));
            put(f, 0x18, new byte[] { 0x5D, 0x1C, (byte) 0x9E, (byte) 0xA3 });
        }
        assertEquals("RMCP01", RomHeaders.read(p.toFile()).get(IdKind.GC_GAME_ID));

        Path q = tmp.resolve("linux.iso");
        try (RandomAccessFile f = new RandomAccessFile(q.toFile(), "rw")) {
            f.setLength(0x100);
            put(f, 0, s("ABCDEF"));
        }
        assertTrue(RomHeaders.read(q.toFile()).isEmpty());
    }

    @Test
    @DisplayName("a file too short to hold a header is simply nameless")
    void tooShort(@TempDir Path tmp) throws Exception {
        Path p = tmp.resolve("stub.3ds");
        try (RandomAccessFile f = new RandomAccessFile(p.toFile(), "rw")) {
            f.setLength(16);
        }
        assertTrue(RomHeaders.read(p.toFile()).isEmpty());
    }
}
