package com.tarikbc.emubackup;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A backup store in an ordinary directory.
 *
 * <p>Archives are streamed to a {@code .ebtmp} sibling and renamed on commit, so an interrupted
 * run leaves a stray temp file rather than a truncated archive that looks complete.
 *
 * <p>Android-free by design; see {@code test.sh}.
 */
public final class LocalFolderSink implements BackupSink {

    private static final String TEMP = ".ebtmp";

    private final File root;

    public LocalFolderSink(String rootPath) throws IOException {
        this.root = new File(rootPath);
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IOException("could not create backup folder: " + rootPath);
        }
    }

    public String rootPath() {
        return root.getAbsolutePath();
    }

    @Override public String describe() {
        return root.getAbsolutePath();
    }

    @Override public void ensureVersion(String versionId) throws IOException {
        File d = new File(root, versionId);
        if (!d.isDirectory() && !d.mkdirs()) throw new IOException("could not create " + d);
    }

    @Override public OutputStream createArchive(String versionId, String name) throws IOException {
        ensureVersion(versionId);
        return new FileOutputStream(new File(new File(root, versionId), name + TEMP));
    }

    @Override public void commitArchive(String versionId, String name) throws IOException {
        File dir = new File(root, versionId);
        File tmp = new File(dir, name + TEMP);
        File dst = new File(dir, name);
        if (!tmp.isFile()) throw new IOException("nothing staged for " + versionId + "/" + name);
        if (dst.exists() && !dst.delete()) throw new IOException("could not replace " + dst);
        if (!tmp.renameTo(dst)) throw new IOException("could not commit " + tmp);
    }

    @Override public void discardArchive(String versionId, String name) {
        File tmp = new File(new File(root, versionId), name + TEMP);
        if (tmp.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    @Override public void writeFile(String versionId, String name, byte[] body) throws IOException {
        ensureVersion(versionId);
        writeAtomic(new File(new File(root, versionId), name), body);
    }

    @Override public InputStream openFile(String versionId, String name) throws IOException {
        return new FileInputStream(new File(new File(root, versionId), name));
    }

    @Override public boolean hasFile(String versionId, String name) {
        return new File(new File(root, versionId), name).isFile();
    }

    @Override public List<String> listVersions() {
        File[] kids = root.listFiles();
        List<String> out = new ArrayList<>();
        if (kids == null) return out;
        for (File f : kids) if (f.isDirectory() && VersionId.looksLikeOne(f.getName())) out.add(f.getName());
        java.util.Collections.sort(out);
        return out;
    }

    @Override public List<String> listFiles(String versionId) {
        File[] kids = new File(root, versionId).listFiles();
        List<String> out = new ArrayList<>();
        if (kids == null) return out;
        for (File f : kids) if (f.isFile() && !f.getName().endsWith(TEMP)) out.add(f.getName());
        java.util.Collections.sort(out);
        return out;
    }

    @Override public void writeRootFile(String name, byte[] body) throws IOException {
        writeAtomic(new File(root, name), body);
    }

    @Override public byte[] readRootFile(String name) throws IOException {
        try (InputStream in = new FileInputStream(new File(root, name))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    @Override public boolean hasRootFile(String name) {
        return new File(root, name).isFile();
    }

    @Override public void deleteVersion(String versionId) throws IOException {
        File d = new File(root, versionId);
        if (!d.isDirectory()) return;
        File[] kids = d.listFiles();
        if (kids != null) for (File f : kids) {
            if (!f.delete()) throw new IOException("could not delete " + f);
        }
        if (!d.delete()) throw new IOException("could not delete " + d);
    }

    /**
     * The index in particular must never be observed half-written: a truncated {@code index.json}
     * would make every existing backup look like it had vanished.
     */
    private static void writeAtomic(File dst, byte[] body) throws IOException {
        File tmp = new File(dst.getPath() + TEMP);
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(body);
            o.getFD().sync();
        }
        if (dst.exists() && !dst.delete()) throw new IOException("could not replace " + dst);
        if (!tmp.renameTo(dst)) throw new IOException("could not commit " + tmp);
    }
}
