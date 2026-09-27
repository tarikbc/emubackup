package com.tarikbc.emubackup;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Cocoon keeps art under downloaded_media/<system>/<kind>/<ROM file base>.<ext>. */
class GameArtTest {

    private static final String MK8 = "Mario Kart 8 Deluxe [0100152000022000][v0] (6.77 GB)";

    private static File touch(Path p) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, "x");
        return p.toFile();
    }

    @Test
    @DisplayName("a Switch ROM's icon and hero are found by title id and by basename")
    void resolvesByTitleIdAndBasename(@TempDir Path tmp) throws Exception {
        Path media = tmp.resolve("Cocoon/downloaded_media");
        File icon = touch(media.resolve("switch/icon/" + MK8 + ".jpg"));
        File hero = touch(media.resolve("switch/hero/" + MK8 + ".png"));
        File rom = tmp.resolve("ROMs/switch/" + MK8 + ".nsp").toFile();

        GameArt.Builder b = new GameArt.Builder(media.toFile());
        b.add(rom, RomFilenameParser.parse(rom.getName()));
        GameArt.Index idx = b.build();

        assertEquals(icon, idx.icon(IdKind.SWITCH_TITLE_ID, "0100152000022000"));
        assertEquals(icon, idx.icon(IdKind.SWITCH_TITLE_ID, "0100152000022000".toLowerCase()));
        assertEquals(hero, idx.hero(IdKind.SWITCH_TITLE_ID, "0100152000022000"));
        assertEquals(icon, idx.icon(IdKind.ROM_BASENAME, MK8));
        assertNull(idx.icon(IdKind.SWITCH_TITLE_ID, "0000000000000000"));
        assertFalse(idx.isEmpty());
    }

    @Test
    @DisplayName("a hero without an icon, and an icon without a hero, are both fine")
    void partialArt(@TempDir Path tmp) throws Exception {
        Path media = tmp.resolve("Cocoon/downloaded_media");
        File icon = touch(media.resolve("switch/icon/" + MK8 + ".webp"));
        File rom = tmp.resolve("ROMs/switch/" + MK8 + ".nsp").toFile();
        GameArt.Builder b = new GameArt.Builder(media.toFile());
        b.add(rom, RomFilenameParser.parse(rom.getName()));
        GameArt.Index idx = b.build();
        assertEquals(icon, idx.icon(IdKind.SWITCH_TITLE_ID, "0100152000022000"));
        assertNull(idx.hero(IdKind.SWITCH_TITLE_ID, "0100152000022000"));
    }

    @Test
    @DisplayName("a GameCube id also answers to its four-character Wii NAND form")
    void gameCubeAlias(@TempDir Path tmp) throws Exception {
        Path media = tmp.resolve("Cocoon/downloaded_media");
        File icon = touch(media.resolve("gc/icon/Zelda [GZLE01].png"));
        File rom = tmp.resolve("ROMs/gc/Zelda [GZLE01].iso").toFile();
        GameArt.Builder b = new GameArt.Builder(media.toFile());
        b.add(rom, RomFilenameParser.parse(rom.getName()));
        GameArt.Index idx = b.build();
        assertEquals(icon, idx.icon(IdKind.GC_GAME_ID, "GZLE01"));
        assertEquals(icon, idx.icon(IdKind.GC_GAME_ID, "GZLE"));
    }

    @Test
    @DisplayName("no Cocoon folder means an empty index, not an error")
    void missingCocoonIsEmpty(@TempDir Path tmp) throws Exception {
        File rom = tmp.resolve("ROMs/switch/" + MK8 + ".nsp").toFile();
        GameArt.Builder b = new GameArt.Builder(tmp.resolve("nowhere").toFile());
        b.add(rom, RomFilenameParser.parse(rom.getName()));
        assertTrue(b.build().isEmpty());
        assertTrue(GameArt.Index.EMPTY.isEmpty());
    }

    @Test
    @DisplayName("a ROM whose name carries no id is still found by the id read from its header")
    void headerIdKeysArt(@TempDir Path tmp) throws Exception {
        Path media = tmp.resolve("Cocoon/downloaded_media");
        File icon = touch(media.resolve("gc/icon/Animal Crossing (USA).png"));
        File rom = tmp.resolve("ROMs/gc/Animal Crossing (USA).rvz").toFile();
        GameArt.Builder b = new GameArt.Builder(media.toFile());
        b.add(rom, RomFilenameParser.parse(rom.getName()),
                java.util.Collections.singletonMap(IdKind.GC_GAME_ID, "GAFE01"));
        GameArt.Index idx = b.build();
        assertEquals(icon, idx.icon(IdKind.GC_GAME_ID, "GAFE01"));
        assertEquals(icon, idx.icon(IdKind.GC_GAME_ID, "GAFE"));
        assertEquals(icon, idx.icon(IdKind.ROM_BASENAME, "Animal Crossing (USA)"));
    }
}
