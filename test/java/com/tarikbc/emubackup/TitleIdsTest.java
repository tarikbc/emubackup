package com.tarikbc.emubackup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TitleIdsTest {

    @Test void wiiNandHexIsTheAsciiGameId() {
        assertEquals("SSQE", TitleIds.wiiNandGameId("53535145"));
        assertEquals("RMCE", TitleIds.wiiNandGameId("524d4345"));
    }

    @Test void wiiSystemTitlesAreNotGames() {
        assertNull(TitleIds.wiiNandGameId("00000002"));
        assertNull(TitleIds.wiiNandGameId("not-hex"));
    }

    @Test void threeDsSystemCategories() {
        assertTrue(TitleIds.is3dsSystem("0004001000021000"));    // a system app
        assertTrue(TitleIds.is3dsSystem("0004003000008202"));    // an applet
        assertFalse(TitleIds.is3dsSystem("0004000000086300"));   // Animal Crossing: New Leaf
        assertFalse(TitleIds.is3dsSystem("0004000000031C00"));   // Pilotwings Resort, a retail game
        assertFalse(TitleIds.is3dsSystem("junk"));
        assertTrue(TitleIds.is3dsAddOn("0004008C0007AF00"));
        assertFalse(TitleIds.is3dsAddOn("0004000000086300"));
    }
}
