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
        // badge, name, in the order the filter chips show them
        { "SW", "Switch" },
        { "GC", "GameCube" },
        { "WII", "Wii" },
        { "3DS", "3DS" },
        { "DS", "DS" },
        { "PS1", "PlayStation" },
        { "PS2", "PlayStation 2" },
        { "PS3", "PlayStation 3" },
        { "PSP", "PSP" },
        { "VITA", "PS Vita" },
        { "DC", "Dreamcast" },
        { "RA", "RetroArch" },
        { "MC", "Minecraft" },
        { "AND", "Android games" },
        { "CH", "Clone Hero" },
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

    /** Position in the chip order; unknown badges sort last. */
    public static int rank(String badge) {
        for (int i = 0; i < TABLE.length; i++) if (TABLE[i][0].equals(badge)) return i;
        return TABLE.length;
    }
}
