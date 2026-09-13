package com.tarikbc.emubackup;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reads bundled assets. The thinnest possible Android wrapper, kept separate so that
 * {@code TargetRegistry} — which does the actual parsing and validation — stays
 * android-free and therefore JVM-testable against the same file on disk.
 */
public final class Assets {

    public static byte[] readBytes(Context ctx, String name) throws IOException {
        try (InputStream in = ctx.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    public static String readString(Context ctx, String name) throws IOException {
        return new String(readBytes(ctx, name), java.nio.charset.StandardCharsets.UTF_8);
    }

    private Assets() {}
}
