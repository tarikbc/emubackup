package com.tarikbc.emubackup;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PathPatternTest {

    @Test
    @DisplayName("the Eden case: profile-major, game-minor")
    void edenSaveTree() {
        PathPattern p = PathPattern.compile("user/save/{account}/{profile}/{game}/**");
        Map<String, String> m = p.match(
                "user/save/0000000000000000/F255133E7DABC494CD4B3089D53DB2DB/0100152000022000/save.dat");
        assertNotNull(m);
        assertEquals("0000000000000000", m.get("account"));
        assertEquals("F255133E7DABC494CD4B3089D53DB2DB", m.get("profile"));
        assertEquals("0100152000022000", m.get("game"));
    }

    @Test
    @DisplayName("** matches zero trailing components as well as many")
    void trailingWildcardMatchesNothing() {
        PathPattern p = PathPattern.compile("{game}/**");
        assertEquals("ULUS10336", p.match("ULUS10336").get("game"));
        assertEquals("ULUS10336", p.match("ULUS10336/a/b/c.dat").get("game"));
    }

    @Test void literalsMustMatchExactly() {
        PathPattern p = PathPattern.compile("user/save/{account}/**");
        assertNull(p.match("other/save/0000/x"));
        assertNull(p.match("user/other/0000/x"));
    }

    @Test
    @DisplayName("without a trailing ** the component count must match exactly")
    void exactArityWithoutWildcard() {
        PathPattern p = PathPattern.compile("{a}/{b}");
        assertNotNull(p.match("x/y"));
        assertNull(p.match("x"));
        assertNull(p.match("x/y/z"));
    }

    @Test void nonMatchingPathReturnsNullNotAPartialMap() {
        // A caller must never have to check whether every expected key is present.
        PathPattern p = PathPattern.compile("a/{game}/**");
        assertNull(p.match("b/x/y"));
    }

    @Test void emptyComponentsNeverMatch() {
        PathPattern p = PathPattern.compile("{a}/{b}");
        assertNull(p.match("x/"));
        assertNull(p.match("/y"));
        assertNull(p.match(""));
        assertNull(p.match(null));
    }

    @Test
    @DisplayName("** in the middle is rejected because the match would be ambiguous")
    void wildcardMustBeLast() {
        assertThrows(IllegalArgumentException.class, () -> PathPattern.compile("a/**/b"));
    }

    @Test void malformedPatternsThrow() {
        assertThrows(IllegalArgumentException.class, () -> PathPattern.compile(""));
        assertThrows(IllegalArgumentException.class, () -> PathPattern.compile("/leading"));
        assertThrows(IllegalArgumentException.class, () -> PathPattern.compile("a//b"));
        assertThrows(IllegalArgumentException.class, () -> PathPattern.compile("{}"));
        assertThrows(IllegalArgumentException.class, () -> PathPattern.compile("{a}/{a}"));
        assertThrows(IllegalArgumentException.class, () -> PathPattern.compile("pre{a}post"));
    }

    @Test void captureNamesAreReported() {
        assertEquals(java.util.Arrays.asList("account", "profile", "game"),
                PathPattern.compile("user/save/{account}/{profile}/{game}/**").captureNames());
    }
}
