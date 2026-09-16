package com.tarikbc.emubackup;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GroupingTest {

    private static FileStat f(String p, long mtime) { return new FileStat(p, 100, mtime); }

    private static Set<String> gameKeys(List<SaveGroup> gs) {
        Set<String> out = new HashSet<>();
        for (SaveGroup g : gs) out.add(g.gameKey);
        return out;
    }

    @Test
    @DisplayName("the Eden tree splits by profile and by game")
    void edenProfileAndGame() {
        Grouping g = Grouping.ofPattern(
                PathPattern.compile("user/save/{account}/{profile}/{game}/**"),
                IdKind.SWITCH_TITLE_ID, IdKind.SWITCH_USER_UUID, null);

        List<SaveGroup> groups = GroupBuilder.build("eden-saves", g, Arrays.asList(
                f("user/save/0000000000000000/F255133E7DABC494CD4B3089D53DB2DB/0100152000022000/a.dat", 30),
                f("user/save/0000000000000000/F255133E7DABC494CD4B3089D53DB2DB/0100152000022000/b.dat", 40),
                f("user/save/0000000000000000/F255133E7DABC494CD4B3089D53DB2DB/01007EF00011E000/c.dat", 20),
                f("user/save/0000000000000000/85DB91DCBD304DA4C24810CF9BF88573/01007EF00011E000/d.dat", 90)));

        assertEquals(3, groups.size(), "two games for one profile, one for the other");

        // Newest first, so the save most likely being looked for is at the top.
        assertEquals("01007EF00011E000", groups.get(0).gameKey);
        assertEquals("85DB91DCBD304DA4C24810CF9BF88573", groups.get(0).profileKey);

        SaveGroup mk8 = null;
        for (SaveGroup s : groups) if ("0100152000022000".equals(s.gameKey)) mk8 = s;
        assertNotNull(mk8);
        assertEquals(2, mk8.files.size());
        assertEquals(200, mk8.bytes);
        assertEquals("F255133E7DABC494CD4B3089D53DB2DB/0100152000022000", mk8.key());
    }

    @Test
    @DisplayName("a PSP save folder normalises to the bare game id")
    void pspIdIsNormalised() {
        Grouping g = Grouping.ofPattern(PathPattern.compile("{game}/**"),
                IdKind.PSP_GAME_ID, null, null);
        List<SaveGroup> groups = GroupBuilder.build("ppsspp-saves", g, Arrays.asList(
                f("ULUS10336DATA00/DATA.BIN", 10),
                f("ULUS10336SYSTEM/SETTINGS.BIN", 20)));
        // Both folders belong to the same game and must not appear as two.
        assertEquals(1, groups.size());
        assertEquals("ULUS10336", groups.get(0).gameKey);
    }

    @Test
    @DisplayName("a 3DS title id split across two directories is rejoined")
    void n3dsTitleIdIsRejoined() {
        Grouping g = Grouping.ofPattern(
                PathPattern.compile("Nintendo 3DS/{id0}/{id1}/title/{hi}/{game}/**"),
                IdKind.N3DS_TITLE_ID, null, null);
        List<SaveGroup> groups = GroupBuilder.build("azahar-sdmc", g, Arrays.asList(
                f("Nintendo 3DS/0000/1111/title/00040000/001a0500/data/00000001.sav", 10)));
        assertEquals("00040000001A0500", groups.get(0).gameKey);
    }

    @Test
    @DisplayName("a filename rule matches the basename, not the path")
    void filenameRuleUsesBasename() {
        Grouping g = Grouping.ofFilenameRegex(
                Pattern.compile("^(?<game>.+)\\.(srm|sav)$"), IdKind.ROM_BASENAME, null);
        List<SaveGroup> groups = GroupBuilder.build("retroarch-saves", g, Arrays.asList(
                f("VBA Next/Wario Land 4 (USA, Europe).srm", 10),
                f("Gambatte/Hamtaro.srm", 20)));
        assertEquals(2, groups.size());
        assertTrue(gameKeys(groups).contains("Wario Land 4 (USA, Europe)"));
        assertTrue(gameKeys(groups).contains("Hamtaro"));
    }

    @Test
    @DisplayName("a target with no grouping rule is one indivisible unit")
    void noGroupingIsWholeTarget() {
        List<SaveGroup> groups = GroupBuilder.build("ps2-memcards", null, Arrays.asList(
                f("mcd001.ps2", 10), f("mcd002.ps2", 20)));
        assertEquals(1, groups.size());
        assertTrue(groups.get(0).isWholeTarget());
        assertNull(groups.get(0).gameKey);
        assertEquals(2, groups.get(0).files.size());
    }

    @Test
    @DisplayName("files no rule matches are kept in an ungrouped bucket, never dropped")
    void unmatchedFilesSurvive() {
        // A rule needing two components, against a file that has only one. Note that a bare
        // "{game}/**" would match a root-level file and call it a game, which is why this uses a
        // deeper pattern to produce a genuine non-match.
        Grouping g = Grouping.ofPattern(PathPattern.compile("saves/{game}/**"),
                IdKind.SWITCH_TITLE_ID, null, null);
        List<SaveGroup> groups = GroupBuilder.build("t", g, Arrays.asList(
                f("saves/0100152000022000/a.dat", 10),
                f("elsewhere.dat", 20)));

        int total = 0;
        for (SaveGroup s : groups) total += s.files.size();
        assertEquals(2, total, "a file vanished from the grouping");

        SaveGroup last = groups.get(groups.size() - 1);
        assertTrue(last.isUngrouped(), "ungrouped leftovers must sort last");
        assertEquals(1, last.files.size());
    }

    @Test
    @DisplayName("an empty target produces no groups rather than an empty one")
    void emptyTarget() {
        assertTrue(GroupBuilder.build("t", null, new ArrayList<>()).isEmpty());
    }
}
