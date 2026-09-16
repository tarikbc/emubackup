package com.tarikbc.emubackup;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GameNamesTest {

    @Test
    @DisplayName("real Switch dump filenames yield the title id and a clean title")
    void switchDumps() {
        RomFilenameParser.Rom r = RomFilenameParser.parse(
                "A Short Hike [01004890117B2000][v0] (0.30 GB).nsp");
        assertEquals("A Short Hike", r.displayName);
        assertEquals("01004890117B2000", r.ids.get(IdKind.SWITCH_TITLE_ID));

        assertEquals("Balatro",
                RomFilenameParser.parse("Balatro [0100CD801CE5E000][v0] (0.08 GB).nsp").displayName);
    }

    @Test
    @DisplayName("region and revision tags are stripped from the title")
    void tagsAreStripped() {
        assertEquals("Fur Fighters", RomFilenameParser.parse(
                "Fur Fighters (USA) (En,Fr,De,Es) (Rev B).chd").displayName);
        assertEquals("Pokemon Platinum",
                RomFilenameParser.parse("Pokemon Platinum.zip").displayName);
    }

    @Test
    @DisplayName("disc serials normalise across the spellings dumps use")
    void discSerials() {
        String a = RomFilenameParser.parse("God of War [SLUS-20946].iso").ids.get(IdKind.PS2_SERIAL);
        String b = RomFilenameParser.parse("SLUS_209.46.God of War.iso").ids.get(IdKind.PS2_SERIAL);
        assertEquals("SLUS-20946", a);
        assertEquals(a, b, "the same disc must not produce two different keys");
    }

    @Test void pspIds() {
        assertEquals("ULUS10336",
                RomFilenameParser.parse("Patapon [ULUS10336].iso").ids.get(IdKind.PSP_GAME_ID));
    }

    @Test
    @DisplayName("an unknown id renders as itself rather than null or an error")
    void unknownFallsBackToTheId() {
        GameNames n = GameNames.empty();
        assertEquals("0100152000022000", n.lookup(IdKind.SWITCH_TITLE_ID, "0100152000022000"));
        assertFalse(n.isKnown(IdKind.SWITCH_TITLE_ID, "0100152000022000"));
        assertEquals("", n.lookup(IdKind.SWITCH_TITLE_ID, null));
    }

    @Test
    @DisplayName("a name derived from the user's library beats the shipped seed")
    void derivedBeatsSeed() {
        GameNames n = GameNames.builder()
                .seed(IdKind.SWITCH_TITLE_ID, "0100152000022000", "Mario Kart 8 Deluxe")
                .derived(IdKind.SWITCH_TITLE_ID, "0100152000022000", "MK8D (my rip)")
                .build();
        assertEquals("MK8D (my rip)", n.lookup(IdKind.SWITCH_TITLE_ID, "0100152000022000"));
    }

    @Test void lookupIsCaseInsensitiveForHexIds() {
        GameNames n = GameNames.builder()
                .derived(IdKind.SWITCH_TITLE_ID, "0100152000022000", "Mario Kart 8 Deluxe").build();
        assertEquals("Mario Kart 8 Deluxe", n.lookup(IdKind.SWITCH_TITLE_ID, "0100152000022000"));
        assertEquals("Mario Kart 8 Deluxe", n.lookup(IdKind.SWITCH_TITLE_ID, "0100152000022000".toLowerCase()));
    }

    @Test
    @DisplayName("building from a parsed ROM registers every id it carries")
    void derivedFromRom() {
        GameNames n = GameNames.builder()
                .derivedFrom(RomFilenameParser.parse("Patapon [ULUS10336].iso")).build();
        assertEquals("Patapon", n.lookup(IdKind.PSP_GAME_ID, "ULUS10336"));
        assertEquals("Patapon", n.lookup(IdKind.ROM_BASENAME, "Patapon"));
    }

    @Test void handlesJunkInput() {
        assertEquals("", RomFilenameParser.parse("").displayName);
        assertEquals("", RomFilenameParser.parse(null).displayName);
        assertEquals("[]", RomFilenameParser.parse("[].nsp").displayName,
                "a name that is nothing but tags falls back rather than becoming empty");
    }
}
