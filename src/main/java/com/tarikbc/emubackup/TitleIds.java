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

    /**
     * True for a 3DS title id in the system categories (00040010 system apps, 00040030
     * applets). Retail games and the built-in applications share 00040000 and cannot be told
     * apart by range: Pilotwings Resort is 00031C00, next to Face Raiders at 00030700, so no
     * guess is made there.
     */
    /** True for a 3DS title id in the add-on content category (0004008C): DLC saves, not a game. */
    public static boolean is3dsAddOn(String titleId) {
        return titleId != null && titleId.matches("[0-9a-fA-F]{16}")
                && titleId.substring(0, 8).equalsIgnoreCase("0004008C");
    }

    public static boolean is3dsSystem(String titleId) {
        if (titleId == null || !titleId.matches("[0-9a-fA-F]{16}")) return false;
        String hi = titleId.substring(0, 8).toUpperCase(java.util.Locale.ROOT);
        return hi.equals("00040010") || hi.equals("00040030");
    }
}
