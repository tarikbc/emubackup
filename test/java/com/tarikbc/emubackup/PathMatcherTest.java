package com.tarikbc.emubackup;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PathMatcherTest {

    private static PathMatcher m(List<String> inc, List<String> exc, boolean recursive) {
        return PathMatcher.compile(inc, exc, recursive, false);
    }

    @Test
    @DisplayName("the RetroArch case: VMU saves match, 4 GB of BIOS beside them does not")
    void retroarchVmuVersusBios() {
        // RetroArch/system holds both the Dreamcast VMU saves and roughly 4 GB of BIOS
        // images. Getting this wrong in either direction is the worst failure the app has.
        PathMatcher p = m(asList("vmu_save_*.bin", "dc_nvmem*.bin"), emptyList(), false);
        assertTrue(p.matches("vmu_save_A1.bin"));
        assertTrue(p.matches("dc_nvmem.bin"));
        assertFalse(p.matches("dc_boot.bin"));
        assertFalse(p.matches("naomi.zip"));
        assertFalse(p.matches("scph5500.bin"));
        // and nothing from a subdirectory, because recursion is off
        assertFalse(p.matches("dc/vmu_save_A1.bin"));
    }

    @Test
    @DisplayName("the melonDS case: .sav matches while a multi-GB ROM beside it does not")
    void melondsSavesVersusRoms() {
        PathMatcher p = m(asList("**/*.sav", "**/*.dsv"), emptyList(), true);
        assertTrue(p.matches("Pokemon.sav"));
        assertTrue(p.matches("sub/Pokemon.dsv"));
        assertFalse(p.matches("Pokemon.zip"));
        assertFalse(p.matches("Mario Kart DS.nds"));
    }

    @Test void starDoesNotCrossComponents() {
        PathMatcher p = m(asList("*.keys"), emptyList(), true);
        assertTrue(p.matches("prod.keys"));
        assertFalse(p.matches("sub/prod.keys"));
    }

    @Test void doubleStarCrossesComponents() {
        PathMatcher p = m(asList("**"), emptyList(), true);
        assertTrue(p.matches("a"));
        assertTrue(p.matches("a/b/c.dat"));
    }

    @Test
    @DisplayName("**/ matches zero leading directories as well as many")
    void leadingDoubleStarIsOptional() {
        PathMatcher p = m(asList("**/*.tmp"), emptyList(), true);
        assertTrue(p.matches("a.tmp"));
        assertTrue(p.matches("x/a.tmp"));
        assertTrue(p.matches("x/y/a.tmp"));
    }

    @Test
    @DisplayName("trailing /** matches the directory itself and anything under it")
    void trailingDoubleStar() {
        PathMatcher p = m(asList("cache/**"), emptyList(), true);
        assertTrue(p.matches("cache"));
        assertTrue(p.matches("cache/a"));
        assertTrue(p.matches("cache/a/b"));
        assertFalse(p.matches("other/a"));
    }

    @Test
    @DisplayName("exclude always beats include")
    void excludeWins() {
        PathMatcher p = m(asList("**"), asList("**/cache/**", "**/*.tmp"), true);
        assertTrue(p.matches("save.dat"));
        assertFalse(p.matches("save.tmp"));
        assertFalse(p.matches("cache/x"));
        assertFalse(p.matches("a/cache/x/y"));
    }

    @Test
    @DisplayName("recursive:false confines matching to direct children")
    void nonRecursive() {
        PathMatcher p = m(asList("*.b"), emptyList(), false);
        assertTrue(p.matches("GTASAsf1.b"));
        assertFalse(p.matches("sub/GTASAsf1.b"));
    }

    @Test void questionMarkIsOneCharAndNeverASlash() {
        PathMatcher p = m(asList("GTASAsf?.b"), emptyList(), true);
        assertTrue(p.matches("GTASAsf1.b"));
        assertFalse(p.matches("GTASAsf10.b"));
        assertFalse(p.matches("a/b.b"));
    }

    @Test
    @DisplayName("regex metacharacters in a glob are literal")
    void metacharactersAreEscaped() {
        PathMatcher p = m(asList("save(1).dat", "a+b.dat"), emptyList(), true);
        assertTrue(p.matches("save(1).dat"));
        assertTrue(p.matches("a+b.dat"));
        assertFalse(p.matches("save1.dat"));
        assertFalse(p.matches("aab.dat"));
    }

    @Test void caseInsensitiveMode() {
        PathMatcher p = PathMatcher.compile(asList("*.SAV"), emptyList(), true, true);
        assertTrue(p.matches("game.sav"));
        assertFalse(PathMatcher.compile(asList("*.SAV"), emptyList(), true, false).matches("game.sav"));
    }

    @Test void emptyPathNeverMatches() {
        assertFalse(m(asList("**"), emptyList(), true).matches(""));
        assertFalse(m(asList("**"), emptyList(), true).matches(null));
    }

    @Test
    @DisplayName("an empty include list is refused at compile time")
    void emptyIncludeThrows() {
        assertThrows(IllegalArgumentException.class, () -> m(emptyList(), emptyList(), true));
    }

    @Test
    @DisplayName("absolute or backslash globs are refused")
    void malformedGlobsThrow() {
        assertThrows(IllegalArgumentException.class, () -> m(asList("/abs/path"), emptyList(), true));
        assertThrows(IllegalArgumentException.class, () -> m(asList("a\\b"), emptyList(), true));
    }
}
