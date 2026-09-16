package com.tarikbc.emubackup;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Catalogue names read like catalogue entries; the app shows what a person would say. */
class TitleCleanTest {

    @Test void articleComesFirstAndSubtitleGetsAColon() {
        assertEquals("The Legend of Zelda: Ocarina of Time 3D",
                RomFilenameParser.parse("Legend of Zelda, The - Ocarina of Time 3D (USA) (En,Fr,Es).3ds").displayName);
        assertEquals("Mario Kart: Double Dash!!",
                RomFilenameParser.parse("Mario Kart - Double Dash!! (USA).rvz").displayName);
        assertEquals("A Short Hike",
                RomFilenameParser.parse("A Short Hike [01004890117B2000][v0] (0.30 GB).nsp").displayName);
    }

    @Test void plainNamesAreLeftAlone() {
        assertEquals("Pilotwings Resort",
                RomFilenameParser.parse("Pilotwings Resort (USA) (En,Fr,Es).3ds").displayName);
        assertEquals("Bomberman Generation",
                RomFilenameParser.parse("Bomberman Generation (USA) (Rev 1).rvz").displayName);
    }
}
