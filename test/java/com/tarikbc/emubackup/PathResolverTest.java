package com.tarikbc.emubackup;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PathResolverTest {

    private final PathResolver r = new PathResolver("/storage/emulated/0");

    @Test void expandsExt() {
        assertEquals("/storage/emulated/0/ROMs/switch/saves", r.resolve("{EXT}/ROMs/switch/saves", null));
        assertEquals("/storage/emulated/0", r.resolve("{EXT}", null));
    }

    @Test void expandsDataWithPackage() {
        assertEquals("/storage/emulated/0/Android/data/org.dolphinemu.dolphinemu/files/GC",
                r.resolve("{DATA}/files/GC", "org.dolphinemu.dolphinemu"));
    }

    @Test
    @DisplayName("a {DATA} root without a package is refused, not silently resolved")
    void dataWithoutPackageThrows() {
        assertThrows(IllegalArgumentException.class, () -> r.resolve("{DATA}/files/GC", null));
    }

    @Test
    @DisplayName("a root with no variable is refused rather than treated as absolute")
    void literalRootThrows() {
        // Accepting "/sdcard/..." would bypass the {EXT} indirection the whole design rests on.
        assertThrows(IllegalArgumentException.class, () -> r.resolve("/sdcard/ROMs", null));
        assertThrows(IllegalArgumentException.class, () -> r.resolve("ROMs/switch", null));
    }

    @Test
    @DisplayName("path traversal in a root is rejected")
    void traversalThrows() {
        // The registry can be overridden by a user-supplied file, so a root is untrusted input.
        assertThrows(IllegalArgumentException.class, () -> r.resolve("{EXT}/ROMs/../../etc", null));
        assertThrows(IllegalArgumentException.class, () -> r.resolve("{DATA}/../other", "a.b.c"));
    }

    @Test
    @DisplayName("a variable in the middle of a path is not expanded")
    void variableMustLead() {
        assertThrows(IllegalArgumentException.class, () -> r.resolve("{EXTRA}/x", null));
        assertThrows(IllegalArgumentException.class, () -> r.resolve("x/{EXT}/y", null));
    }

    @Test void normalisesRepeatedAndTrailingSlashes() {
        assertEquals("/storage/emulated/0/ROMs/nds", r.resolve("{EXT}//ROMs///nds/", null));
    }

    @Test void joins() {
        assertEquals("/a/b/c", PathResolver.join("/a/b", "c"));
        assertEquals("/a/b/c", PathResolver.join("/a/b/", "/c"));
        assertEquals("/a/b", PathResolver.join("/a/b", ""));
    }

    @Test void relativizes() {
        assertEquals("x/y.sav", PathResolver.relativize("/root", "/root/x/y.sav"));
        assertEquals("", PathResolver.relativize("/root", "/root"));
    }

    @Test
    @DisplayName("relativize refuses a path outside the root")
    void relativizeOutsideThrows() {
        // Returning the absolute path would put absolute entries in a zip and break hand-restore.
        assertThrows(IllegalArgumentException.class, () -> PathResolver.relativize("/root", "/other/x"));
        assertThrows(IllegalArgumentException.class, () -> PathResolver.relativize("/root", "/rootlike/x"));
    }

    @Test void emptyExtRootThrows() {
        assertThrows(IllegalArgumentException.class, () -> new PathResolver(""));
    }
}
