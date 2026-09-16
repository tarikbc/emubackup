package com.tarikbc.emubackup;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Flattens one version, and everything its chains extract from, into a single ordinary zip.
 *
 * <p>The store format is already open and already recoverable by hand, and {@code RESTORE.txt}
 * explains how. But "open the newest archive, then the full it builds on, then extract them in
 * this order" is a set of instructions, and instructions are a thing that can be got wrong at
 * the worst moment. This produces the end state instead: one file, one unzip, the saves as they
 * were.
 *
 * <p>Each file is verified against the manifest on its way through, exactly as a restore would,
 * so an export is also a check. A file that fails is left out and named rather than written
 * wrong, because a zip that unpacks into subtly corrupt saves is worse than one that is
 * visibly short.
 *
 * <p>Android-free by design; see {@code test.sh}.
 */
public final class ExportRunner {

    public interface Listener {
        void onProgress(String target, int done, int total);
        boolean isCancelled();

        Listener SILENT = new Listener() {
            @Override public void onProgress(String t, int d, int n) { }
            @Override public boolean isCancelled() { return false; }
        };
    }

    public static final class Result {
        public final int files;
        public final long bytes;
        public final boolean cancelled;

        /** Files that could not be written, named. Empty when the export is complete. */
        public final List<String> problems;

        Result(int files, long bytes, boolean cancelled, List<String> problems) {
            this.files = files;
            this.bytes = bytes;
            this.cancelled = cancelled;
            this.problems = problems;
        }

        public boolean ok() {
            return !cancelled && problems.isEmpty();
        }

        public String summary() {
            if (cancelled) return "Stopped. The file is incomplete.";
            String head = files + (files == 1 ? " file, " : " files, ") + Sizes.human(bytes);
            return problems.isEmpty() ? head + ". Every one matched its checksum."
                    : head + ", and " + problems.size() + " left out.";
        }
    }

    private ExportRunner() {}

    /**
     * @param out     the zip is written here and the stream is closed on the way out.
     * @param tempDir scratch space. Each file is staged here before it enters the zip, because a
     *                zip entry cannot be un-written once started and {@link ArchiveReader} only
     *                knows a file is sound after it has streamed all of it.
     */
    public static Result export(BackupSink store, String versionId, OutputStream out,
                                File tempDir, Listener listener) throws IOException {
        Listener l = listener == null ? Listener.SILENT : listener;
        List<String> problems = new ArrayList<>();
        int[] files = { 0 };
        long[] bytes = { 0 };

        Manifest m;
        try (InputStream in = store.openFile(versionId, "manifest.json")) {
            m = Manifest.fromJson(BackupRunner.readAll(in));
        }

        if (!tempDir.isDirectory() && !tempDir.mkdirs()) {
            throw new IOException("could not create a scratch directory at " + tempDir);
        }

        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            // The manifest and the restore notes travel with the data, so the export explains
            // itself to someone who opens it a year from now with no app installed.
            entry(zip, "manifest.json", m.toJson().getBytes(StandardCharsets.UTF_8));
            entry(zip, "RESTORE.txt", exportReadme(m).getBytes(StandardCharsets.UTF_8));

            int total = 0;
            for (ManifestTarget t : m.targets) if (!t.files.isEmpty()) total++;
            int done = 0;

            for (ManifestTarget t : m.targets) {
                if (t.files.isEmpty()) continue;
                if (l.isCancelled()) return new Result(files[0], bytes[0], true, problems);
                l.onProgress(t.id, done, total);

                Map<String, ManifestFile> wanted = new LinkedHashMap<>();
                for (ManifestFile f : t.files) wanted.put(f.path, f);

                ArchiveReader.Result r = ArchiveReader.extract(store, t.chain, wanted,
                        new StagingWriter(zip, t.id, tempDir, files, bytes, problems),
                        new ArchiveReader.Listener() {
                            @Override public void onFile(String p, int i, int n, long d, long tt) { }
                            @Override public boolean isCancelled() { return l.isCancelled(); }
                        });

                for (String missing : r.missing) problems.add(t.id + "/" + missing + ": not in the backup");
                for (String bad : r.corrupt) problems.add(t.id + "/" + bad + ": checksum did not match");
                done++;
            }
            l.onProgress(null, done, total);
        }
        return new Result(files[0], bytes[0], false, problems);
    }

    /** Stages each file to disk, then copies it into the zip once the reader says it is sound. */
    private static final class StagingWriter implements ArchiveReader.Writer {
        private final ZipOutputStream zip;
        private final String prefix;
        private final File tempDir;
        private final int[] files;
        private final long[] bytes;
        private final List<String> problems;
        private final Map<String, File> staged = new LinkedHashMap<>();

        StagingWriter(ZipOutputStream zip, String prefix, File tempDir,
                      int[] files, long[] bytes, List<String> problems) {
            this.zip = zip;
            this.prefix = prefix;
            this.tempDir = tempDir;
            this.files = files;
            this.bytes = bytes;
            this.problems = problems;
        }

        @Override public OutputStream open(String relPath) throws IOException {
            File f = File.createTempFile("export", ".part", tempDir);
            staged.put(relPath, f);
            return new FileOutputStream(f);
        }

        @Override public void commit(String relPath, ManifestFile expected) throws IOException {
            File f = staged.remove(relPath);
            if (f == null) return;
            try {
                ZipEntry e = new ZipEntry(prefix + "/" + relPath);
                if (expected != null && expected.mtimeMs > 0) e.setTime(expected.mtimeMs);
                zip.putNextEntry(e);
                try (InputStream in = new FileInputStream(f)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        zip.write(buf, 0, n);
                        bytes[0] += n;
                    }
                }
                zip.closeEntry();
                files[0]++;
            } catch (IOException ex) {
                problems.add(prefix + "/" + relPath + ": " + ex.getMessage());
            } finally {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }

        @Override public void discard(String relPath) {
            File f = staged.remove(relPath);
            if (f != null) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    private static void entry(ZipOutputStream zip, String name, byte[] body) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(body);
        zip.closeEntry();
    }

    static String exportReadme(Manifest m) {
        StringBuilder b = new StringBuilder();
        b.append("EmuBackup export of ").append(m.version).append("\n\n");
        b.append("This is every file in that backup, already merged. There is no chain to\n");
        b.append("follow and nothing else to download: unzip it and the saves are here, one\n");
        b.append("directory per target, laid out as they were on the device.\n\n");
        b.append("Each file was checked against its recorded SHA-256 on the way in. Anything\n");
        b.append("that did not match was left out rather than written wrong.\n\n");
        b.append("Where each directory came from:\n\n");
        for (ManifestTarget t : m.targets) {
            if (t.files.isEmpty()) continue;
            b.append("  ").append(t.id).append("/\n");
            b.append("      was ").append(t.root).append('\n');
            b.append("      ").append(t.files.size())
                    .append(t.files.size() == 1 ? " file" : " files").append('\n');
        }
        b.append("\nmanifest.json beside this file records every path, size, checksum and\n");
        b.append("modification time, and is the same manifest the app itself reads.\n");
        return b.toString();
    }
}
