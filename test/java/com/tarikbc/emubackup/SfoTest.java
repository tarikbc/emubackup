package com.tarikbc.emubackup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SfoTest {

    /** Builds a PARAM.SFO with two string entries, laid out the way Sony's tools do. */
    private static byte[] sfo(String[][] entries) {
        ByteArrayOutputStream keys = new ByteArrayOutputStream();
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        ByteArrayOutputStream index = new ByteArrayOutputStream();
        for (String[] e : entries) {
            int keyOff = keys.size(), dataOff = data.size();
            keys.writeBytes(e[0].getBytes(StandardCharsets.US_ASCII));
            keys.write(0);
            byte[] v = e[1].getBytes(StandardCharsets.UTF_8);
            data.writeBytes(v);
            data.write(0);
            while (data.size() % 4 != 0) data.write(0);
            index.writeBytes(new byte[] { (byte) keyOff, (byte) (keyOff >> 8), 0x04, 0x02 });
            int len = v.length + 1;
            index.writeBytes(new byte[] { (byte) len, (byte) (len >> 8), 0, 0 });
            index.writeBytes(new byte[] { (byte) len, (byte) (len >> 8), 0, 0 });
            index.writeBytes(new byte[] { (byte) dataOff, (byte) (dataOff >> 8), 0, 0 });
        }
        int keysAt = 20 + index.size();
        int dataAt = keysAt + keys.size();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] { 0, 'P', 'S', 'F', 1, 1, 0, 0 });
        out.writeBytes(new byte[] { (byte) keysAt, (byte) (keysAt >> 8), 0, 0 });
        out.writeBytes(new byte[] { (byte) dataAt, (byte) (dataAt >> 8), 0, 0 });
        out.writeBytes(new byte[] { (byte) entries.length, 0, 0, 0 });
        out.writeBytes(index.toByteArray());
        out.writeBytes(keys.toByteArray());
        out.writeBytes(data.toByteArray());
        return out.toByteArray();
    }

    @Test void readsTheTitle() {
        Map<String, String> m = Sfo.parse(sfo(new String[][] {
                { "SAVEDATA_DIRECTORY", "UCUS98612P0000" },
                { "TITLE", "Daxter" } }));
        assertEquals("Daxter", m.get("TITLE"));
        assertEquals("UCUS98612P0000", m.get("SAVEDATA_DIRECTORY"));
    }

    @Test void garbageIsEmpty() {
        assertTrue(Sfo.parse(new byte[] { 1, 2, 3 }).isEmpty());
        assertTrue(Sfo.parse(null).isEmpty());
    }
}
