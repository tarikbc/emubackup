package com.tarikbc.emubackup;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GameFilterTest {

    @Test
    @DisplayName("case and accents do not matter")
    void foldsCaseAndAccents() {
        assertTrue(GameFilter.matches("Mário Kart 8 Deluxe", "Tarik", "MARIO"));
        assertTrue(GameFilter.matches("Pokemon", null, "pokémon"));
    }

    @Test
    @DisplayName("every word of the query must appear somewhere")
    void everyWordMustMatch() {
        assertTrue(GameFilter.matches("Mario Kart 8 Deluxe", "Tarik", "kart 8"));
        assertFalse(GameFilter.matches("Mario Kart 8 Deluxe", "Tarik", "mario zelda"));
    }

    @Test
    @DisplayName("the profile name counts too")
    void profileNameMatches() {
        assertTrue(GameFilter.matches("Mario Kart 8 Deluxe", "Tarik", "tarik"));
        assertFalse(GameFilter.matches("Mario Kart 8 Deluxe", null, "tarik"));
    }

    @Test
    @DisplayName("an empty query matches everything")
    void emptyQueryMatchesAll() {
        assertTrue(GameFilter.matches("Anything", null, ""));
        assertTrue(GameFilter.matches("Anything", null, "   "));
        assertTrue(GameFilter.matches("Anything", null, null));
    }
}
