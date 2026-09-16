package com.tarikbc.emubackup;

import android.content.Context;
import android.os.Environment;
import java.io.File;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/**
 * Builds the game-name database from the ROM library already on the device.
 *
 * <p>Reads <em>filenames only</em>. Nothing opens a ROM, so indexing a 549 GB library costs a
 * directory walk and no file I/O. That is the whole reason this can run on demand rather than
 * shipping a curated table that would be stale, incomplete, and someone else's work.
 */
public final class RomIndexer {

    /** Two levels covers {@code ROMs/<system>/<file>} and folders like "Updates and DLC". */
    private static final int MAX_DEPTH = 3;

    /** A guard against an unexpected tree; the real library is a few thousand entries. */
    private static final int MAX_ENTRIES = 50_000;

    public static final class Index {
        public final GameNames names;
        public final int filesSeen;
        public final int idsFound;

        Index(GameNames names, int filesSeen, int idsFound) {
            this.names = names;
            this.filesSeen = filesSeen;
            this.idsFound = idsFound;
        }
    }

    /** Walks the ROM roots and derives names. Blocking; call from a background thread. */
    public static Index build(Context ctx) {
        GameNames.Builder b = GameNames.builder();
        seed(b);

        int seen = 0, ids = 0;
        File roms = new File(Environment.getExternalStorageDirectory(), "ROMs");
        if (roms.isDirectory()) {
            Deque<Entry> stack = new ArrayDeque<>();
            stack.push(new Entry(roms, 0));
            while (!stack.isEmpty() && seen < MAX_ENTRIES) {
                Entry e = stack.pop();
                File[] kids = e.dir.listFiles();
                if (kids == null) continue;
                for (File f : kids) {
                    if (seen >= MAX_ENTRIES) break;
                    if (f.isDirectory()) {
                        if (e.depth + 1 < MAX_DEPTH) stack.push(new Entry(f, e.depth + 1));
                        continue;
                    }
                    seen++;
                    RomFilenameParser.Rom rom = RomFilenameParser.parse(f.getName());
                    if (rom.hasIds()) ids++;
                    b.derivedFrom(rom);
                    // The id inside the file, for the containers whose names never carry it.
                    for (Map.Entry<IdKind, String> h : RomHeaders.read(f).entrySet()) {
                        b.derived(h.getKey(), h.getValue(), rom.displayName);
                        ids++;
                    }
                }
            }
        }
        return new Index(b.build(), seen, ids);
    }

    /**
     * A handful of identifiers observed first-hand on the reference device, so a first launch is
     * not entirely raw hex. Not an attempt at completeness; the derived layer above is.
     */
    private static void seed(GameNames.Builder b) {
        b.seed(IdKind.SWITCH_TITLE_ID, "0100152000022000", "Mario Kart 8 Deluxe");
        b.seed(IdKind.SWITCH_TITLE_ID, "01007EF00011E000", "The Legend of Zelda: Breath of the Wild");
        b.seed(IdKind.SWITCH_TITLE_ID, "0100F2C0115B6000", "The Legend of Zelda: Tears of the Kingdom");
        b.seed(IdKind.SWITCH_TITLE_ID, "01006A800016E000", "Super Smash Bros. Ultimate");
        b.seed(IdKind.SWITCH_TITLE_ID, "0100000000010000", "Super Mario Odyssey");
        b.seed(IdKind.SWITCH_TITLE_ID, "010015100B514000", "Super Mario Bros. Wonder");
        b.seed(IdKind.SWITCH_TITLE_ID, "0100CD801CE5E000", "Balatro");
        b.seed(IdKind.SWITCH_TITLE_ID, "0100A5C00D162000", "Cuphead");
        b.seed(IdKind.SWITCH_TITLE_ID, "010095001B12A000", "Go-Go Town");
        b.seed(IdKind.SWITCH_TITLE_ID, "01008CF01BAAC000", "The Legend of Zelda: Echoes of Wisdom");
        b.seed(IdKind.SWITCH_TITLE_ID, "0100FCF002A58000", "Ultimate Chicken Horse");
        b.seed(IdKind.SWITCH_TITLE_ID, "010042C011476000", "Unrailed!");
        b.seed(IdKind.SWITCH_TITLE_ID, "01006FE013472000", "Mario Party Superstars");
        b.seed(IdKind.SWITCH_TITLE_ID, "01004200189F4000", "Factorio");
        b.seed(IdKind.SWITCH_TITLE_ID, "010015F005C8E000", "Tricky Towers");
        b.seed(IdKind.SWITCH_TITLE_ID, "01004890117B2000", "A Short Hike");
        b.seed(IdKind.SWITCH_TITLE_ID, "0100379013A62000", "Very Very Valet");
    }

    private static final class Entry {
        final File dir; final int depth;
        Entry(File dir, int depth) { this.dir = dir; this.depth = depth; }
    }

    private RomIndexer() {}
}
