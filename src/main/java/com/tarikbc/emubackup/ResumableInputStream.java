package com.tarikbc.emubackup;

import java.io.IOException;
import java.io.InputStream;

/**
 * A stream that picks up where it stopped when the link under it dies.
 *
 * <p>A 53 MB archive read over a stalling connection used to start again from zero after each
 * stall, which on the Thor meant it never finished. The opener is asked for the bytes from a
 * given offset (an HTTP Range request, for Drive), and a read that fails mid-stream reopens at
 * the byte it reached. Only a failure between bytes is retried; the end of the stream is the
 * end. Android-free; see {@code test.sh}.
 */
public final class ResumableInputStream extends InputStream {

    public interface Opener {
        /** The bytes from {@code offset} to the end. */
        InputStream open(long offset) throws IOException;
    }

    private final Opener opener;
    private final int maxAttempts;
    private InputStream current;
    private long position;
    private int attempts;

    /** @param maxAttempts how many times the stream may be opened in all, the first one included */
    public ResumableInputStream(Opener opener, int maxAttempts) {
        this.opener = opener;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    private InputStream stream() throws IOException {
        if (current == null) {
            attempts++;
            current = opener.open(position);
        }
        return current;
    }

    /** Drops the dead stream. The next read reopens at {@link #position}, or gives up. */
    private IOException stalled(IOException e) {
        try {
            if (current != null) current.close();
        } catch (IOException ignored) {
            // Already broken.
        }
        current = null;
        if (attempts >= maxAttempts) return e;
        return null;
    }

    @Override public int read() throws IOException {
        while (true) {
            try {
                int b = stream().read();
                if (b >= 0) position++;
                return b;
            } catch (IOException e) {
                IOException fatal = stalled(e);
                if (fatal != null) throw fatal;
            }
        }
    }

    @Override public int read(byte[] b, int off, int len) throws IOException {
        while (true) {
            try {
                int n = stream().read(b, off, len);
                if (n > 0) position += n;
                return n;
            } catch (IOException e) {
                IOException fatal = stalled(e);
                if (fatal != null) throw fatal;
            }
        }
    }

    @Override public void close() throws IOException {
        if (current != null) current.close();
        current = null;
    }
}
