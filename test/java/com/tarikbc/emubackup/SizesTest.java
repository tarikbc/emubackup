package com.tarikbc.emubackup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SizesTest {

    @Test
    @DisplayName("bytes below 1 KB render as a plain byte count")
    void human_bytes() {
        assertEquals("0 B", Sizes.human(0));
        assertEquals("1 B", Sizes.human(1));
        assertEquals("512 B", Sizes.human(512));
        assertEquals("1023 B", Sizes.human(1023));
    }

    @Test
    @DisplayName("unit boundaries step exactly at 1024")
    void human_boundaries() {
        assertEquals("1 KB", Sizes.human(1024));
        assertEquals("1 MB", Sizes.human(1024L * 1024));
        assertEquals("1 GB", Sizes.human(1024L * 1024 * 1024));
        assertEquals("1 TB", Sizes.human(1024L * 1024 * 1024 * 1024));
    }

    @Test
    @DisplayName("one decimal below 10 units, none at or above")
    void human_precision() {
        assertEquals("1.5 KB", Sizes.human(1536));
        assertEquals("9.9 MB", Sizes.human(10380902L));   // just under 10 MB
        assertEquals("14 MB", Sizes.human(14L * 1024 * 1024));
        assertEquals("143 MB", Sizes.human(149946368L));  // the real Eden saves figure
    }

    @Test
    @DisplayName("a trailing .0 is dropped")
    void human_trimsTrailingZero() {
        assertEquals("2 KB", Sizes.human(2048));
        assertEquals("4 MB", Sizes.human(4L * 1024 * 1024));
    }

    @Test
    @DisplayName("a value that would round to 1024 of a unit promotes instead")
    void human_promotesInsteadOfRendering1024() {
        // 1023.97 KB. Formatting in KB would print "1024 KB", which reads as broken.
        assertEquals("1 MB", Sizes.human(1048550L));
    }

    @Test
    @DisplayName("real inventory figures format as they do in the plan")
    void human_matchesInventory() {
        assertEquals("663 KB", Sizes.human(678912L));     // Dolphin GC + Wii saves
        assertEquals("386 MB", Sizes.human(404750336L));  // PS2 save states
    }

    @Test
    @DisplayName("a negative size throws rather than rendering as zero")
    void human_rejectsNegative() {
        // A negative byte count always means a bug upstream, usually a bad subtraction
        // in the diff engine. Rendering "0 B" would hide it.
        assertThrows(IllegalArgumentException.class, () -> Sizes.human(-1));
    }

    @Test
    @DisplayName("delta is signed, and zero has no sign")
    void delta_signs() {
        assertEquals("0 B", Sizes.delta(0));
        assertEquals("+512 B", Sizes.delta(512));
        assertEquals("-512 B", Sizes.delta(-512));
        assertEquals("+2.1 MB", Sizes.delta(2202010L));
    }

    @Test
    @DisplayName("delta of Long.MIN_VALUE throws rather than silently flipping sign")
    void delta_longMinValue() {
        // Math.abs(Long.MIN_VALUE) is still negative, so this reaches human() as a
        // negative and throws. Documented here so the behaviour is intentional, not
        // an accident nobody noticed.
        assertThrows(IllegalArgumentException.class, () -> Sizes.delta(Long.MIN_VALUE));
    }
}
