package com.tarikbc.emubackup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An export is only worth having if unzipping it reproduces the saves. So every test here
 * unzips the result and compares it against what was on the device.
 */
class ExportRunnerTest {

    private static final String REGISTRY =
            "{\"registryVersion\":1,\"emulators\":[{"
            + "\"id\":\"emu\",\"label\":\"Emu\",\"packages\":[\"a.b.c\"],\"targets\":["
            + "{\"id\":\"saves\",\"label\":\"Saves\",\"category\":\"SAVE\",\"tier\":\"SHARED\","
            + "\"root\":\"{EXT}/saves\",\"include\":[\"**\"],\"maxBytes\":104857600},"
            + "{\"id\":\"cards\",\"label\":\"Cards\",\"category\":\"SAVE\",\"tier\":\"SHARED\","
            + "\"root\":\"{EXT}/cards\",\"include\":[\"**\"],\"maxBytes\":104857600}"
            + "]}]}";

    private static void write(Path p, String body) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, body);
    }

    private static BackupRunner runner(Path ext, LocalFolderSink sink) {
        TargetRegistry reg = TargetRegistry.parse(REGISTRY);
        PathResolver res = new PathResolver(ext.toString());
        FileSource src = new LocalFileSource();
        Capabilities caps = new Capabilities(true, false, false);
        ScanEngine scan = new ScanEngine(res, src, null, caps, PackagePresence.ALL_PRESENT);
        return new BackupRunner(reg, scan, res, src, null, sink, caps,
                EmulatorVersions.UNKNOWN, PackagePresence.ALL_PRESENT)
                .withDevice("1.0-test", "TestDevice", 33);
    }

    /** Text that does not compress, so an archive of it is about as large as the input. */
    private static String noise(int n) {
        StringBuilder b = new StringBuilder(n);
        java.util.Random r = new java.util.Random(42);
        String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        for (int i = 0; i < n; i++) b.append(alphabet.charAt(r.nextInt(alphabet.length())));
        return b.toString();
    }

    private static Map<String, String> unzip(byte[] zip) throws IOException {
        Map<String, String> out = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) body.write(buf, 0, n);
                out.put(e.getName(), body.toString(StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    @Test
    @DisplayName("an export of a chained version unzips to the live tree, with no chain to follow")
    void exportFlattensTheChain(@TempDir Path tmp) throws Exception {
        Path ext = tmp.resolve("device");
        LocalFolderSink sink = new LocalFolderSink(tmp.resolve("store").toString());

        write(ext.resolve("saves/a.dat"), "one");
        write(ext.resolve("saves/sub/b.dat"), "two");
        write(ext.resolve("cards/c.bin"), "card");
        runner(ext, sink).run(null, "manual", 1_000L, BackupRunner.SILENT);

        // Only saves change, so v2 is an incremental and cards stays back in v1.
        write(ext.resolve("saves/a.dat"), "one-changed");
        String v2 = runner(ext, sink).run(null, "manual", 2_000L, BackupRunner.SILENT).versionId;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExportRunner.Result r = ExportRunner.export(sink, v2, out, tmp.resolve("scratch").toFile(), null);

        assertTrue(r.ok(), r.problems.toString());
        Map<String, String> got = unzip(out.toByteArray());

        assertEquals("one-changed", got.get("saves/a.dat"));
        assertEquals("two", got.get("saves/sub/b.dat"));
        // The proof that matters: cards lives only in v1 and still comes out of a v2 export.
        assertEquals("card", got.get("cards/c.bin"));
        assertEquals(3, r.files);
    }

    @Test
    @DisplayName("the export explains itself without the app")
    void exportIsSelfDescribing(@TempDir Path tmp) throws Exception {
        Path ext = tmp.resolve("device");
        LocalFolderSink sink = new LocalFolderSink(tmp.resolve("store").toString());
        write(ext.resolve("saves/a.dat"), "one");
        write(ext.resolve("cards/c.bin"), "card");
        String v = runner(ext, sink).run(null, "manual", 1_000L, BackupRunner.SILENT).versionId;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExportRunner.export(sink, v, out, tmp.resolve("scratch").toFile(), null);
        Map<String, String> got = unzip(out.toByteArray());

        assertTrue(got.containsKey("manifest.json"), got.keySet().toString());
        assertTrue(got.containsKey("RESTORE.txt"), got.keySet().toString());
        String readme = got.get("RESTORE.txt");
        assertTrue(readme.contains(v), readme);
        assertTrue(readme.contains("saves/"), readme);
        // It must say where the files came from, or restoring by hand is guesswork.
        assertTrue(readme.contains(ext.resolve("saves").toString()), readme);
    }

    @Test
    @DisplayName("a corrupt archive costs that target, not the export, and is named")
    void corruptionIsReportedNotWritten(@TempDir Path tmp) throws Exception {
        Path ext = tmp.resolve("device");
        Path store = tmp.resolve("store");
        LocalFolderSink sink = new LocalFolderSink(store.toString());
        write(ext.resolve("saves/a.dat"), "one");
        // Big and incompressible, so truncating the archive genuinely cuts this file in half.
        // A four-byte file survives truncation intact inside the first local header, which is
        // how the first version of this test managed to assert nothing at all.
        write(ext.resolve("cards/c.bin"), noise(400_000));
        String v = runner(ext, sink).run(null, "manual", 1_000L, BackupRunner.SILENT).versionId;

        Path victim = store.resolve(v).resolve("cards.full.zip");
        byte[] whole = Files.readAllBytes(victim);
        assertTrue(whole.length > 100_000, "archive too small to truncate meaningfully");
        Files.write(victim, Arrays.copyOf(whole, whole.length / 2));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExportRunner.Result r = ExportRunner.export(sink, v, out, tmp.resolve("scratch").toFile(), null);

        assertFalse(r.ok(), "a truncated archive exported as though it were fine");
        assertTrue(r.problems.toString().contains("cards"), r.problems.toString());

        // The healthy target still made it out, and the broken file is absent rather than wrong.
        Map<String, String> got = unzip(out.toByteArray());
        assertEquals("one", got.get("saves/a.dat"));
        assertFalse(got.containsKey("cards/c.bin"), "wrote a file that failed its checksum");
    }

    @Test
    @DisplayName("stopping leaves a file that says it is incomplete")
    void cancellation(@TempDir Path tmp) throws Exception {
        Path ext = tmp.resolve("device");
        LocalFolderSink sink = new LocalFolderSink(tmp.resolve("store").toString());
        write(ext.resolve("saves/a.dat"), "one");
        String v = runner(ext, sink).run(null, "manual", 1_000L, BackupRunner.SILENT).versionId;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExportRunner.Result r = ExportRunner.export(sink, v, out, tmp.resolve("scratch").toFile(),
                new ExportRunner.Listener() {
                    @Override public void onProgress(String t, int d, int n) { }
                    @Override public boolean isCancelled() { return true; }
                });
        assertTrue(r.cancelled);
        assertFalse(r.ok());
        assertTrue(r.summary().contains("incomplete"), r.summary());
    }

    @Test
    @DisplayName("scratch files do not survive the export")
    void scratchIsCleanedUp(@TempDir Path tmp) throws Exception {
        Path ext = tmp.resolve("device");
        LocalFolderSink sink = new LocalFolderSink(tmp.resolve("store").toString());
        write(ext.resolve("saves/a.dat"), "one");
        write(ext.resolve("saves/b.dat"), "two");
        String v = runner(ext, sink).run(null, "manual", 1_000L, BackupRunner.SILENT).versionId;

        Path scratch = tmp.resolve("scratch");
        ExportRunner.export(sink, v, new ByteArrayOutputStream(), scratch.toFile(), null);

        List<Path> left = new ArrayList<>();
        try (var s = Files.list(scratch)) {
            s.forEach(left::add);
        }
        assertTrue(left.isEmpty(), "left staging files behind: " + left);
    }
}
