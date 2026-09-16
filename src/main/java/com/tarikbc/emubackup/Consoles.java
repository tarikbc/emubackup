package com.tarikbc.emubackup;

/**
 * Which console a target's games belong to, as a short badge and a plain name.
 *
 * <p>Derived from the emulator id rather than stored in the registry, because it is a display
 * concern and the registry is a description of paths. The test walks the shipped registry so a
 * new emulator cannot land without a badge. Android-free; see {@code test.sh}.
 */
public final class Consoles {

    private static final String[][] TABLE = {
        // badge, name, tint (ARGB hex), in the order the filter chips show them. The tint is a
        // nod to each console's own colour; the letters carry the meaning, never the tint alone.
        { "SW", "Switch", "#FFE8453C" },
        { "GC", "GameCube", "#FF8B7CD8" },
        { "WII", "Wii", "#FF7FC4E8" },
        { "3DS", "3DS", "#FFE57373" },
        { "DS", "DS", "#FFB0BEC5" },
        { "PS1", "PlayStation", "#FFB8B8B8" },
        { "PS2", "PlayStation 2", "#FF4F7FD9" },
        { "PS3", "PlayStation 3", "#FF6A8FE0" },
        { "PSP", "PSP", "#FF5AA0E6" },
        { "VITA", "PS Vita", "#FF6FB3EA" },
        { "DC", "Dreamcast", "#FFF4A261" },
        { "RA", "RetroArch", "#FF8FA3C0" },
        { "MC", "Minecraft", "#FF6FBF4F" },
        { "AND", "Android games", "#FFA4C639" },
        { "CH", "Clone Hero", "#FFFF9F43" },
    };

    private Consoles() {}

    public static String badge(String emulatorId, String targetId) {
        if (emulatorId == null) return "?";
        switch (emulatorId) {
            case "eden": return "SW";
            case "ps2": return "PS2";
            case "ppsspp": return "PSP";
            case "azahar":
            case "citra-legacy": return "3DS";
            case "dolphin": return targetId != null && targetId.contains("-wii") ? "WII" : "GC";
            case "duckstation": return "PS1";
            case "melonds": return "DS";
            case "retroarch": return targetId != null && targetId.endsWith("-vmu") ? "DC" : "RA";
            case "vita3k": return "VITA";
            case "aps3e":
            case "rpcsx": return "PS3";
            case "gtasa": return "AND";
            case "minecraft-bedrock":
            case "amethyst": return "MC";
            case "clonehero": return "CH";
            default: return "?";
        }
    }

    /** The plain name for a badge, for a filter chip. Unknown badges come back as themselves. */
    public static String name(String badge) {
        for (String[] row : TABLE) if (row[0].equals(badge)) return row[1];
        return badge;
    }

    /** The console's tint as ARGB, for the badge; a neutral grey for an unknown badge. */
    public static int tint(String badge) {
        for (String[] row : TABLE) if (row[0].equals(badge)) return (int) Long.parseLong(row[2].substring(1), 16);
        return 0xFF9AA4B2;
    }

    /** Position in the chip order; unknown badges sort last. */
    public static int rank(String badge) {
        for (int i = 0; i < TABLE.length; i++) if (TABLE[i][0].equals(badge)) return i;
        return TABLE.length;
    }
}
