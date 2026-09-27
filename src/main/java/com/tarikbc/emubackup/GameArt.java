package com.tarikbc.emubackup;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Game art borrowed from Cocoon's scrape folder, keyed the way saves are keyed.
 *
 * <p>Cocoon writes what it scraped to {@code Cocoon/downloaded_media/<system>/<kind>/<ROM file
 * base>.<ext>}, where {@code <system>} is the same folder name as {@code ROMs/<system>}, so a
 * ROM file names its own art. The index is built while the ROM library is walked for names
 * and answers by the same ids, with the same aliases {@link GameNames} keeps. Reads nothing
 * but directory entries. Android-free; see {@code test.sh}.
 */
public final class GameArt {

    private static final String[] EXTS = { "jpg", "png", "webp", "jpeg" };

    public static final class Index {
        public static final Index EMPTY = new Index(Collections.emptyMap(), Collections.emptyMap());

        private final Map<String, File> icons, heroes;

        Index(Map<String, File> icons, Map<String, File> heroes) {
            this.icons = icons;
            this.heroes = heroes;
        }

        /** The square icon, or null. */
        public File icon(IdKind kind, String id) {
            return id == null ? null : icons.get(key(kind, id));
        }

        /** The wide hero image, or null. */
        public File hero(IdKind kind, String id) {
            return id == null ? null : heroes.get(key(kind, id));
        }

        public boolean isEmpty() {
            return icons.isEmpty() && heroes.isEmpty();
        }
    }

    public static final class Builder {
        private final File media;
        private final Map<String, File> icons = new HashMap<>();
        private final Map<String, File> heroes = new HashMap<>();

        /** @param media Cocoon's {@code downloaded_media} folder; may not exist */
        public Builder(File media) {
            this.media = media;
        }

        /** Registers whatever art Cocoon holds for one ROM file, under every id the file carries. */
        public void add(File rom, RomFilenameParser.Rom parsed) {
            add(rom, parsed, Collections.emptyMap());
        }

        /**
         * The same, with ids read from inside the file. A GameCube or Wii disc image is named
         * "Animal Crossing (USA).rvz" and carries its id only in its header, while the save is
         * keyed by that id, so without these the art would answer to the basename alone.
         */
        public void add(File rom, RomFilenameParser.Rom parsed, Map<IdKind, String> headerIds) {
            File systemDir = rom.getParentFile();
            if (systemDir == null) return;
            String base = RomFilenameParser.stripExtension(rom.getName());
            File icon = find(systemDir.getName(), "icon", base);
            File hero = find(systemDir.getName(), "hero", base);
            if (icon == null && hero == null) return;
            for (Map.Entry<IdKind, String> e : parsed.ids.entrySet()) {
                put(e.getKey(), e.getValue(), icon, hero);
            }
            for (Map.Entry<IdKind, String> e : headerIds.entrySet()) {
                put(e.getKey(), e.getValue(), icon, hero);
            }
            put(IdKind.ROM_BASENAME, base, icon, hero);
            put(IdKind.ROM_BASENAME, parsed.base, icon, hero);
            put(IdKind.ROM_BASENAME, parsed.displayName, icon, hero);
        }

        private void put(IdKind kind, String id, File icon, File hero) {
            if (id == null || id.isEmpty()) return;
            if (icon != null) icons.putIfAbsent(key(kind, id), icon);
            if (hero != null) heroes.putIfAbsent(key(kind, id), hero);
            // The same aliases GameNames keeps: a 3DS save is keyed by the low half of the title
            // id, a Wii NAND save by the first four characters of the disc id.
            if (kind == IdKind.N3DS_TITLE_ID && id.length() == 16) put(kind, id.substring(8), icon, hero);
            if (kind == IdKind.GC_GAME_ID && id.length() == 6) put(kind, id.substring(0, 4), icon, hero);
        }

        private File find(String system, String kind, String base) {
            File dir = new File(new File(media, system), kind);
            for (String ext : EXTS) {
                File f = new File(dir, base + "." + ext);
                if (f.isFile()) return f;
            }
            return null;
        }

        public Index build() {
            return icons.isEmpty() && heroes.isEmpty() ? Index.EMPTY : new Index(icons, heroes);
        }
    }

    private static String key(IdKind kind, String id) {
        return kind.name() + "|" + (kind == IdKind.ROM_BASENAME ? id : id.toUpperCase(Locale.ROOT));
    }

    private GameArt() {}
}
