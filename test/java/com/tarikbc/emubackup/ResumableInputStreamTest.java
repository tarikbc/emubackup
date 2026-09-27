package com.tarikbc.emubackup;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A download that stalls is resumed from where it stopped, not started over. */
class ResumableInputStreamTest {

    private static final byte[] BODY = new byte[5_000];
    static {
        for (int i = 0; i < BODY.length; i++) BODY[i] = (byte) (i * 7);
    }

    /** Serves the body from an offset, but the first {@code failures} streams die after 1000 bytes. */
    private static final class Flaky implements ResumableInputStream.Opener {
        final List<Long> offsets = new ArrayList<>();
        int failures;

        Flaky(int failures) { this.failures = failures; }

        @Override public InputStream open(long offset) {
            offsets.add(offset);
            byte[] rest = java.util.Arrays.copyOfRange(BODY, (int) offset, BODY.length);
            if (failures > 0) {
                failures--;
                return new InputStream() {
                    int at;
                    @Override public int read() throws IOException {
                        if (at >= 1000) throw new IOException("timeout");
                        return at < rest.length ? rest[at++] & 0xFF : -1;
                    }
                };
            }
            return new ByteArrayInputStream(rest);
        }
    }

    @Test
    @DisplayName("a stalled stream is reopened from the byte it reached")
    void resumesWhereItStopped() throws Exception {
        Flaky f = new Flaky(2);
        byte[] got = readAll(new ResumableInputStream(f, 5));
        assertArrayEquals(BODY, got);
        assertEquals(List.of(0L, 1000L, 2000L), f.offsets);
    }

    @Test
    @DisplayName("after the last allowed attempt the failure is reported, not hidden")
    void givesUpAfterTheAllowedAttempts() {
        Flaky f = new Flaky(10);
        assertThrows(IOException.class, () -> readAll(new ResumableInputStream(f, 3)));
        assertEquals(3, f.offsets.size());
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[777];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
