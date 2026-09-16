package com.tarikbc.emubackup;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads game identifiers and a readable title out of a ROM filename.
 *
 * <p>This is where the name database comes from. Dumps already carry the identifier the save
 * folders are named after — {@code A Short Hike [01004890117B2000][v0].nsp} — so the mapping from
 * {@code 0100152000022000} to a title can be derived from the user's own library instead of
 * shipping a curated table. Only filenames are read; nothing opens a ROM.
 *
 * <p>Android-free by design; see {@code test.sh}.
 */
public final class RomFilenameParser {

    private static final Pattern BRACKET = Pattern.compile("\\[([^\\]]+)\\]");
    private static final Pattern SWITCH_ID = Pattern.compile("^[0-9A-Fa-f]{16}$");
    private static final Pattern GC_ID = Pattern.compile("^[A-Z0-9]{6}$");
    private static final Pattern DISC_SERIAL = Pattern.compile("\\b([A-Z]{4})[-_ ]?(\\d{3})\\.?(\\d{2})\\b");
    private static final Pattern PSP_ID = Pattern.compile("\\b([A-Z]{4}\\d{5})\\b");

    public static final class Rom {
        /** The title with extension, bracket tags and parenthetical tags removed. */
        public final String displayName;
        public final Map<IdKind, String> ids;

        Rom(String displayName, Map<IdKind, String> ids) {
            this.displayName = displayName;
            this.ids = ids;
        }

        public boolean hasIds() {
            return !ids.isEmpty();
        }
    }

    public static Rom parse(String filename) {
        if (filename == null || filename.isEmpty()) {
            return new Rom("", new LinkedHashMap<>());
        }
        String base = stripExtension(filename);
        Map<IdKind, String> ids = new LinkedHashMap<>();

        // Bracketed tags are the reliable source; a dump tool put them there deliberately.
        Matcher b = BRACKET.matcher(base);
        while (b.find()) {
            String tag = b.group(1).trim();
            if (SWITCH_ID.matcher(tag).matches()) {
                ids.putIfAbsent(IdKind.SWITCH_TITLE_ID, tag.toUpperCase(Locale.ROOT));
            } else if (GC_ID.matcher(tag).matches() && !tag.matches("^V\\d+$")) {
                ids.putIfAbsent(IdKind.GC_GAME_ID, tag);
            }
        }

        String upper = base.toUpperCase(Locale.ROOT);

        // SLUS-20946, SLUS_209.46 and SLUS 20946 are all the same disc. Normalised to the
        // hyphenated form so a save folder and a ROM filename agree.
        Matcher d = DISC_SERIAL.matcher(upper);
        if (d.find()) {
            String serial = d.group(1) + "-" + d.group(2) + d.group(3);
            ids.putIfAbsent(IdKind.PS2_SERIAL, serial);
            // The two consoles share the serial format and a filename does not say which it is.
            // Registering under both is harmless: lookup is by kind and id together.
            ids.putIfAbsent(IdKind.PSX_SERIAL, serial);
        }

        Matcher p = PSP_ID.matcher(upper);
        if (p.find()) ids.putIfAbsent(IdKind.PSP_GAME_ID, p.group(1));

        return new Rom(cleanTitle(base), ids);
    }

    /** The filename with its extension removed, if it has a plausible one. */
    static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot <= 0) return filename;
        String ext = filename.substring(dot + 1);
        return ext.length() <= 5 && ext.matches("[A-Za-z0-9]+") ? filename.substring(0, dot) : filename;
    }

    /**
     * Strips the tags dump tools append, leaving the title. Everything from the first bracket or
     * parenthesis onward is a tag: region, revision, version, size, dump flags.
     */
    static String cleanTitle(String base) {
        int cut = base.length();
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            if (c == '[' || c == '(') { cut = i; break; }
        }
        String s = base.substring(0, cut).trim();
        // A name that was nothing but tags falls back to the whole thing rather than to nothing.
        return s.isEmpty() ? base.trim() : s;
    }

    private RomFilenameParser() {}
}
