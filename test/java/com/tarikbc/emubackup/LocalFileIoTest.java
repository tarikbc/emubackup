package com.tarikbc.emubackup;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFileIoTest {

    private final LocalFileSource src = new LocalFileSource();
    private final LocalFileSink sink = new LocalFileSink();

    private static void write(Path p, String body) throws IOException {
        Files.createDirectories(p.getParent());
        Files.write(p, body.getBytes(StandardCharsets.UTF_8));
    }

    private static Set<String> paths(List<FileStat> l) {
        Set<String> s = new HashSet<>();
        for (FileStat f : l) s.add(f.path);
        return s;
    }

    @Test
    @DisplayName("walk returns files with target-relative paths, and no directories")
    void walkReturnsRelativeFiles(@TempDir Path root) throws Exception {
        write(root.resolve("a.sav"), "x");
        write(root.resolve("sub/b.sav"), "yy");
        Files.createDirectories(root.resolve("empty"));

        List<FileStat> out = src.walk(root.toString(), true);
        assertEquals(new HashSet<>(java.util.Arrays.asList("a.sav", "sub/b.sav")), paths(out));
        for (FileStat f : out) assertFalse(f.path.startsWith("/"), "path must be relative: " + f.path);
    }

    @Test void walkRecordsSizeAndMtime(@TempDir Path root) throws Exception {
        write(root.resolve("a.sav"), "hello");
        FileStat f = src.walk(root.toString(), true).get(0);
        assertEquals(5, f.size);
        assertTrue(f.mtimeMs > 0);
    }

    @Test
    @DisplayName("recursive:false stops at direct children")
    void nonRecursiveWalk(@TempDir Path root) throws Exception {
        write(root.resolve("a.sav"), "x");
        write(root.resolve("sub/b.sav"), "y");
        assertEquals(new HashSet<>(java.util.Arrays.asList("a.sav")),
                paths(src.walk(root.toString(), false)));
    }

    @Test
    @DisplayName("symlinks are skipped, not followed")
    void symlinksAreSkipped(@TempDir Path root) throws Exception {
        write(root.resolve("real.sav"), "x");
        Path outside = Files.createTempDirectory("outside");
        write(outside.resolve("secret.sav"), "nope");
        try {
            Files.createSymbolicLink(root.resolve("link"), outside);
            Files.createSymbolicLink(root.resolve("loop"), root);
        } catch (UnsupportedOperationException | IOException e) {
            return; // filesystem does not permit links; nothing to assert
        }
        // A followed link would either pull in unrelated files or loop forever.
        assertEquals(new HashSet<>(java.util.Arrays.asList("real.sav")),
                paths(src.walk(root.toString(), true)));
    }

    @Test
    @DisplayName("an absent root is reported, not treated as empty")
    void missingRoot(@TempDir Path root) {
        String missing = root.resolve("nope").toString();
        assertFalse(src.exists(missing));
        assertThrows(IOException.class, () -> src.walk(missing, true));
    }

    @Test void openReadsBytes(@TempDir Path root) throws Exception {
        write(root.resolve("sub/a.sav"), "payload");
        try (java.io.InputStream in = src.open(root.toString(), "sub/a.sav")) {
            byte[] b = new byte[7];
            assertEquals(7, in.read(b));
            assertEquals("payload", new String(b, StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("nothing appears at the final path until commit")
    void writeIsStagedThenCommitted(@TempDir Path root) throws Exception {
        try (OutputStream o = sink.createTemp(root.toString(), "deep/new.sav")) {
            o.write("data".getBytes(StandardCharsets.UTF_8));
        }
        assertFalse(Files.exists(root.resolve("deep/new.sav")), "must not be visible before commit");
        assertTrue(Files.exists(root.resolve("deep/new.sav.ebtmp")));

        sink.commit(root.toString(), "deep/new.sav");
        assertTrue(Files.exists(root.resolve("deep/new.sav")));
        assertFalse(Files.exists(root.resolve("deep/new.sav.ebtmp")));
        assertEquals("data", new String(Files.readAllBytes(root.resolve("deep/new.sav")), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("an abandoned write leaves the original intact")
    void discardLeavesOriginalUntouched(@TempDir Path root) throws Exception {
        write(root.resolve("save.dat"), "original");
        try (OutputStream o = sink.createTemp(root.toString(), "save.dat")) {
            o.write("half-writ".getBytes(StandardCharsets.UTF_8));
        }
        sink.discardTemp(root.toString(), "save.dat");
        assertEquals("original", new String(Files.readAllBytes(root.resolve("save.dat")), StandardCharsets.UTF_8));
        assertFalse(Files.exists(root.resolve("save.dat.ebtmp")));
    }

    @Test void commitReplacesAnExistingFile(@TempDir Path root) throws Exception {
        write(root.resolve("save.dat"), "old");
        try (OutputStream o = sink.createTemp(root.toString(), "save.dat")) {
            o.write("new".getBytes(StandardCharsets.UTF_8));
        }
        sink.commit(root.toString(), "save.dat");
        assertEquals("new", new String(Files.readAllBytes(root.resolve("save.dat")), StandardCharsets.UTF_8));
    }

    @Test void commitWithoutStagingThrows(@TempDir Path root) {
        assertThrows(IOException.class, () -> sink.commit(root.toString(), "nothing.dat"));
    }

    @Test void discardIsSafeWhenNothingIsStaged(@TempDir Path root) {
        assertDoesNotThrow(() -> sink.discardTemp(root.toString(), "nothing.dat"));
    }

    @Test void setMtimeAndDelete(@TempDir Path root) throws Exception {
        write(root.resolve("a.sav"), "x");
        sink.setMtime(root.toString(), "a.sav", 1_600_000_000_000L);
        assertEquals(1_600_000_000_000L, root.resolve("a.sav").toFile().lastModified());
        sink.delete(root.toString(), "a.sav");
        assertFalse(Files.exists(root.resolve("a.sav")));
        assertDoesNotThrow(() -> sink.delete(root.toString(), "a.sav"));
    }
}
