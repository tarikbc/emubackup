package com.tarikbc.emubackup;

/**
 * The name a game is shown under, from its save group and the ROM library's names. Shared by
 * the Games list and Home, so a game is called the same thing everywhere. Android-free.
 */
public final class GameLabels {

    private GameLabels() {}

    public static String displayName(GameHistory.Entry e, Target t, GameNames names, String badge) {
        if (e.group.isWholeTarget()) return t.label;
        if (e.group.isUngrouped()) return "Other files in " + t.label;
        String key = e.group.gameKey;
        IdKind kind = e.group.gameIdKind;
        if ("dolphin-wii".equals(t.id)) {
            // The NAND keys a save by the hex of its game id; system titles are not games.
            String id = TitleIds.wiiNandGameId(key);
            if (id == null) return "Wii system data \u00b7 " + key;
            if (names.isKnown(IdKind.GC_GAME_ID, id)) return names.lookup(IdKind.GC_GAME_ID, id);
            return "Wii title " + id;
        }
        if (names.isKnown(kind, key)) return names.lookup(kind, key);
        if (kind == IdKind.ROM_BASENAME) return RomFilenameParser.cleanTitle(key);
        if (kind == IdKind.N3DS_TITLE_ID && TitleIds.is3dsSystem(key)) return "3DS system data \u00b7 " + key.substring(8);
        if (kind == IdKind.N3DS_TITLE_ID && TitleIds.is3dsAddOn(key)) return "3DS add-on content \u00b7 " + key.substring(8);
        if (kind == IdKind.N3DS_TITLE_ID && key.length() == 16) return "3DS title " + key.substring(8);
        return Consoles.name(badge) + " title " + key;
    }

    /**
     * When the game last changed: the newer of its files on the device and its newest recorded
     * change in a backup. Not the last backup that held it, which every game shares after a
     * run and which would tie the whole list, and not the file date alone, which a restore
     * sets back to the backup's date and would sink a game just put back.
     */
    public static long recencyMs(GameHistory.Entry e) {
        long at = e.newestMtimeMs();
        if (!e.snapshots.isEmpty()) at = Math.max(at, e.snapshots.get(0).atMs);
        return at;
    }

    /** True when the game's newest snapshot looks reset and is the newest backup there is. */
    public static boolean looksResetNow(GameHistory.Entry e, java.util.List<IndexEntry> index) {
        if (e.snapshots.isEmpty()) return false;
        GameHistory.Snapshot s = e.snapshots.get(0);
        if (!s.looksReset) return false;
        String newest = null;
        long at = 0;
        for (IndexEntry i : index) {
            if (!i.isPreRestore() && i.createdAtMs >= at) {
                at = i.createdAtMs;
                newest = i.id;
            }
        }
        return s.versionId.equals(newest);
    }
}
