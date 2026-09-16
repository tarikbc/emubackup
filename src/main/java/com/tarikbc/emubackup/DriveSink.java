package com.tarikbc.emubackup;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A backup store in Google Drive, producing the same tree as the local one.
 *
 * <p>Archives are staged to a local file first and uploaded on commit. That is not a workaround:
 * it is what gives the upload a known length up front, a checksum before it leaves the device,
 * and the ability to resume mid-file after the process is killed. A 386 MB save-state archive over
 * a handheld's connection needs all three.
 *
 * <p>Android-free by design; see {@code test.sh}.
 */
public final class DriveSink implements BackupSink {

    private static final String ROOT_FOLDER = "EmuBackup";

    private final DriveApi api;
    private final File stagingDir;
    private final DriveApi.ProgressListener listener;

    /** Folder ids resolved this run. Drive lookups are a round trip each, and names are not unique. */
    private final Map<String, String> folderIds = new HashMap<>();
    private String rootId;

    public DriveSink(DriveApi api, File stagingDir, DriveApi.ProgressListener listener) {
        this.api = api;
        this.stagingDir = stagingDir;
        this.listener = listener == null ? DriveApi.SILENT : listener;
        if (!stagingDir.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            stagingDir.mkdirs();
        }
    }

    @Override public String describe() {
        return "Google Drive / " + ROOT_FOLDER;
    }

    private String root() throws IOException {
        if (rootId == null) rootId = api.ensureFolder(ROOT_FOLDER, null);
        return rootId;
    }

    private String versionFolder(String versionId) throws IOException {
        String id = folderIds.get(versionId);
        if (id == null) {
            id = api.ensureFolder(versionId, root());
            folderIds.put(versionId, id);
        }
        return id;
    }

    @Override public void ensureVersion(String versionId) throws IOException {
        versionFolder(versionId);
    }

    @Override public OutputStream createArchive(String versionId, String name) throws IOException {
        File staged = staged(versionId, name);
        File parent = staged.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("could not create staging dir " + parent);
        }
        return new FileOutputStream(staged);
    }

    @Override public void commitArchive(String versionId, String name) throws IOException {
        File staged = staged(versionId, name);
        if (!staged.isFile()) throw new IOException("nothing staged for " + versionId + "/" + name);
        try {
            api.upload(versionFolder(versionId), name, staged,
                    DriveApi.props(versionId, targetOf(name), "archive"), listener);
        } finally {
            // Removed either way. Keeping a failed upload's staging file would quietly fill the
            // device, and the next attempt rebuilds it from the source saves anyway.
            //noinspection ResultOfMethodCallIgnored
            staged.delete();
        }
    }

    @Override public void discardArchive(String versionId, String name) {
        File staged = staged(versionId, name);
        if (staged.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            staged.delete();
        }
    }

    @Override public void writeFile(String versionId, String name, byte[] body) throws IOException {
        File tmp = staged(versionId, name);
        File parent = tmp.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(body);
        }
        try {
            // Replaced rather than duplicated: Drive allows two files with the same name in one
            // folder, and a second manifest.json would make the version ambiguous.
            deleteIfPresent(versionId, name);
            api.upload(versionFolder(versionId), name, tmp,
                    DriveApi.props(versionId, null, kindOf(name)), DriveApi.SILENT);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    @Override public InputStream openFile(String versionId, String name) throws IOException {
        DriveApi.RemoteFile f = find(versionId, name);
        if (f == null) throw new IOException("not in Drive: " + versionId + "/" + name);
        return api.download(f.id);
    }

    @Override public boolean hasFile(String versionId, String name) {
        try {
            return find(versionId, name) != null;
        } catch (IOException e) {
            return false;
        }
    }

    @Override public List<String> listVersions() throws IOException {
        List<String> out = new ArrayList<>();
        for (DriveApi.RemoteFile f : api.listFolder(root())) {
            if (f.name != null && VersionId.looksLikeOne(f.name)) out.add(f.name);
        }
        java.util.Collections.sort(out);
        return out;
    }

    @Override public List<String> listFiles(String versionId) throws IOException {
        List<String> out = new ArrayList<>();
        for (DriveApi.RemoteFile f : api.listFolder(versionFolder(versionId))) out.add(f.name);
        java.util.Collections.sort(out);
        return out;
    }

    @Override public void writeRootFile(String name, byte[] body) throws IOException {
        File tmp = new File(stagingDir, "_root_" + name);
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(body);
        }
        try {
            for (DriveApi.RemoteFile f : api.listFolder(root())) {
                if (name.equals(f.name)) api.delete(f.id);
            }
            api.upload(root(), name, tmp, DriveApi.props("root", null, kindOf(name)), DriveApi.SILENT);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    @Override public byte[] readRootFile(String name) throws IOException {
        for (DriveApi.RemoteFile f : api.listFolder(root())) {
            if (name.equals(f.name)) {
                try (InputStream in = api.download(f.id)) {
                    return DriveApi.readAll(in).getBytes(StandardCharsets.UTF_8);
                }
            }
        }
        throw new IOException("not in Drive: " + name);
    }

    @Override public boolean hasRootFile(String name) {
        try {
            for (DriveApi.RemoteFile f : api.listFolder(root())) {
                if (name.equals(f.name)) return true;
            }
        } catch (IOException ignored) {
        }
        return false;
    }

    @Override public void deleteVersion(String versionId) throws IOException {
        String id = folderIds.get(versionId);
        if (id == null) id = api.findFolder(versionId, root());
        if (id == null) return;
        for (DriveApi.RemoteFile f : api.listFolder(id)) api.delete(f.id);
        api.delete(id);
        folderIds.remove(versionId);
    }

    // ------------------------------------------------------------------ helpers

    private DriveApi.RemoteFile find(String versionId, String name) throws IOException {
        for (DriveApi.RemoteFile f : api.listFolder(versionFolder(versionId))) {
            if (name.equals(f.name)) return f;
        }
        return null;
    }

    private void deleteIfPresent(String versionId, String name) throws IOException {
        DriveApi.RemoteFile f = find(versionId, name);
        if (f != null) api.delete(f.id);
    }

    private File staged(String versionId, String name) {
        return new File(new File(stagingDir, versionId), name);
    }

    /** The target id an archive belongs to, recorded so Drive metadata alone can rebuild an index. */
    static String targetOf(String archiveName) {
        int dot = archiveName.indexOf('.');
        return dot <= 0 ? archiveName : archiveName.substring(0, dot);
    }

    static String kindOf(String name) {
        if (name.endsWith(".zip")) return "archive";
        if (name.startsWith("manifest")) return "manifest";
        if (name.startsWith("index")) return "index";
        return "meta";
    }
}
