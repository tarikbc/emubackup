package com.tarikbc.emubackup;

/**
 * What a raw identifier is, when the name is not known.
 *
 * <p>Some ids are not games at all: the Wii System Menu's save, a 3DS's built-in apps. Those
 * deserve a plain label rather than a lookup that can never succeed. Android-free.
 */
public final class TitleIds {

    private TitleIds() {}

    /**
     * Dolphin keeps a Wii NAND save under {@code title/<hi>/<hex>/}, the hex being the ASCII
     * game id. {@code 53535145} is {@code SSQE}. Returns the 4-character id, or null when the
     * hex is not printable ASCII (system titles).
     */
    public static String wiiNandGameId(String hex) {
        if (hex == null || !hex.matches("[0-9a-fA-F]{8}")) return null;
        StringBuilder b = new StringBuilder(4);
        for (int i = 0; i < 8; i += 2) {
            int c = Integer.parseInt(hex.substring(i, i + 2), 16);
            if (!Character.isLetterOrDigit(c) || c > 0x7E) return null;
            b.append((char) c);
        }
        return b.toString();
    }

    /** True for a 3DS title id that is one of the console's own built-in apps, not a game. */
    public static boolean is3dsBuiltIn(String titleId) {
        if (titleId == null || !titleId.matches("[0-9a-fA-F]{16}")) return false;
        String hi = titleId.substring(0, 8).toUpperCase(java.util.Locale.ROOT);
        long lo = Long.parseLong(titleId.substring(8), 16);
        // 00040000 with a low id under 0x40000 is the built-in application range (Camera,
        // Sound, Mii Maker, Face Raiders, AR Games, their region variants); 00040010 and
        // 00040030 are system apps and applets.
        return (hi.equals("00040000") && lo < 0x40000) || hi.equals("00040010") || hi.equals("00040030");
    }
}
