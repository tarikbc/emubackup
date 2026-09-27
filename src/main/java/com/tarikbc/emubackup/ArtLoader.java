package com.tarikbc.emubackup;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;

/**
 * Puts Cocoon's art into image views without ever decoding a scraped file on the main thread.
 *
 * <p>Cocoon's icons are full-size scrapes, often over a megabyte each, and a list scrolls past
 * dozens. Each is decoded once, at the size it is shown, and the small result is kept twice:
 * in memory for this run, and as a PNG under the cache dir for the next. A view is tagged
 * with what it asked for, so a bitmap that arrives after the row was recycled for another game
 * is dropped rather than shown on the wrong row.
 */
final class ArtLoader {

    private static final LruCache<String, Bitmap> MEMORY = new LruCache<String, Bitmap>(
            (int) Math.min(Integer.MAX_VALUE, Runtime.getRuntime().maxMemory() / 8)) {
        @Override protected int sizeOf(String key, Bitmap b) {
            return b.getByteCount();
        }
    };

    /**
     * Its own threads. The shell's executor is where the store is read, and a Drive request
     * that hangs there must not hold every thumbnail hostage.
     */
    private static final ExecutorService DECODE = java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "art");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        return t;
    });

    private final Context ctx;
    private final ExecutorService io;
    private final Handler ui;

    ArtLoader(Context ctx, Handler ui) {
        this.ctx = ctx.getApplicationContext();
        this.io = DECODE;
        this.ui = ui;
    }

    /**
     * Shows {@code src} in {@code into} at most {@code maxPx} on its longer side. Null clears
     * the view. The view keeps its last bitmap until the new one is ready only when both are
     * the same file; otherwise it is cleared at once, so a recycled row never shows a stale game.
     */
    void load(ImageView into, File src, int maxPx) {
        if (src == null) {
            into.setTag(null);
            into.setImageBitmap(null);
            return;
        }
        String key = src.getPath() + "@" + maxPx;
        if (key.equals(into.getTag())) return;
        into.setTag(key);
        Bitmap hit = MEMORY.get(key);
        if (hit != null) {
            into.setImageBitmap(hit);
            return;
        }
        into.setImageBitmap(null);
        io.execute(() -> {
            Bitmap b = decode(src, maxPx);
            if (b == null) return;
            MEMORY.put(key, b);
            ui.post(() -> {
                if (key.equals(into.getTag())) into.setImageBitmap(b);
            });
        });
    }

    private Bitmap decode(File src, int maxPx) {
        File cached = new File(new File(ctx.getCacheDir(), "art"),
                Hashes.sha256((src.getPath() + "|" + src.length() + "|" + src.lastModified() + "|" + maxPx)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".png");
        if (cached.isFile()) {
            Bitmap b = BitmapFactory.decodeFile(cached.getPath());
            if (b != null) return b;
        }
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(src.getPath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize(Math.max(bounds.outWidth, bounds.outHeight), maxPx);
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap big = BitmapFactory.decodeFile(src.getPath(), opts);
            if (big == null) return null;
            int w = big.getWidth(), h = big.getHeight();
            float scale = Math.min(1f, (float) maxPx / Math.max(w, h));
            Bitmap small = scale < 1f
                    ? Bitmap.createScaledBitmap(big, Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), true)
                    : big;
            if (small != big) big.recycle();
            //noinspection ResultOfMethodCallIgnored
            cached.getParentFile().mkdirs();
            File tmp = new File(cached.getPath() + ".part");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                small.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            if (!tmp.renameTo(cached)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            return small;
        } catch (Exception | OutOfMemoryError broken) {
            // A scrape that will not decode leaves the row with its badge, which is what it had.
            return null;
        }
    }

    /** The largest power of two that still leaves the longer side at or above {@code maxPx}. */
    static int sampleSize(int longest, int maxPx) {
        int s = 1;
        while (longest / (s * 2) >= maxPx) s *= 2;
        return s;
    }
}
