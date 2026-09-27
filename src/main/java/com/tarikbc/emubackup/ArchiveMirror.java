package com.tarikbc.emubackup;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Opens a backup's archive from the nearest copy that holds the wanted files.
 *
 * <p>The files a restore wants often sit in an archive on the device as well: the folder the
 * app wrote to before it moved to Drive, or a store on the SD card. Those archives have other
 * version ids and other bytes, but a file is the same file when its path and SHA-256 match,
 * and every manifest records both. So the wanted files are looked up by content, and when one
 * local archive holds all of them it is read instead of the store's. The archive is streamed
 * once first to be sure it is whole; the reader still checks every file against the manifest.
 * Android-free; see {@code test.sh}.
 */
public final class ArchiveMirror implements ArchiveReader.Opener {

    private final BackupSink store;
    /** {@code sha256|path} to the local archive that holds that file. */
    private final Map<String, File> byFile;
    private final Map<File, Boolean> whole = new HashMap<>();

    /**
     * @param store      where the restore's version lives
     * @param byFile     local archives by the files they hold, from {@link #scan}
     * @param manifestOf kept for callers that resolve manifests; not needed for file matching
     */
    public ArchiveMirror(BackupSink store, Map<String, File> byFile, Function<String, Manifest> manifestOf) {
        this.store = store;
        this.byFile = byFile;
    }

    static String key(String sha256, String path) {
        return sha256 + "|" + path;
    }

    /** Every file every archive under a local store root holds, by content and path. */
    public static Map<String, File> scan(File root) {
        Map<String, File> out = new HashMap<>();
        File[] versions = root == null ? null : root.listFiles();
        if (versions == null) return out;
        for (File v : versions) {
            File mf = new File(v, "manifest.json");
            if (!v.isDirectory() || !mf.isFile()) continue;
            try {
                Manifest m = Manifest.fromJson(new String(java.nio.file.Files.readAllBytes(mf.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8));
                for (ManifestTarget t : m.targets) {
                    for (ManifestFile f : t.files) {
                        // The archive holding a file is the chain entry of the file's version.
                        String ref = null;
                        for (String c : t.chain) if (c.startsWith(f.version + "/")) ref = c;
                        if (ref == null || f.sha256 == null) continue;
                        File a = new File(root, ref);
                        if (a.isFile()) out.put(key(f.sha256, f.path), a);
                    }
                }
            } catch (Exception unreadable) {
                // A folder that is not a version. Nothing to offer from it.
            }
        }
        return out;
    }

    /** The one local archive holding every wanted file, or null. */
    private File localCopy(Map<String, ManifestFile> wanted) {
        if (wanted == null || wanted.isEmpty() || byFile.isEmpty()) return null;
        File one = null;
        for (ManifestFile f : wanted.values()) {
            File a = byFile.get(key(f.sha256, f.path));
            if (a == null || (one != null && !one.equals(a))) return null;
            one = a;
        }
        return one != null && isWhole(one, wanted) ? one : null;
    }

    /** Streamed through once: a damaged zip must fall through to the store, not read as corrupt. */
    private boolean isWhole(File a, Map<String, ManifestFile> wanted) {
        Boolean known = whole.get(a);
        if (known != null) return known;
        boolean ok = false;
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(a))) {
            Set<String> names = new HashSet<>();
            ZipEntry ze;
            byte[] buf = new byte[64 * 1024];
            while ((ze = zin.getNextEntry()) != null) {
                names.add(ze.getName());
                while (zin.read(buf) != -1) { /* the whole entry, so a truncated one throws here */ }
            }
            ok = names.containsAll(wanted.keySet());
        } catch (IOException | RuntimeException damaged) {
            ok = false;
        }
        whole.put(a, ok);
        return ok;
    }

    @Override public String where(String versionId, String archive, Map<String, ManifestFile> wanted) {
        return localCopy(wanted) != null ? "the copy on this device" : null;
    }

    @Override public InputStream open(String versionId, String archive, Map<String, ManifestFile> wanted)
            throws IOException {
        File a = localCopy(wanted);
        if (a != null) return new FileInputStream(a);
        return store.openFile(versionId, archive);
    }

    @Override public InputStream open(String versionId, String archive) throws IOException {
        return store.openFile(versionId, archive);
    }
}
