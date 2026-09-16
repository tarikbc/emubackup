package com.tarikbc.emubackup;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A backup store in a folder the person picked, producing the same tree as the other two.
 *
 * <p>This is the destination that needs no account and no setup: an SD card, a USB drive, or a
 * folder belonging to a sync app that ships a documents provider, in which case the backup leaves
 * the device with no OAuth client involved at all.
 *
 * <p>Archives are staged to a local file and copied in on commit, exactly as {@link DriveSink}
 * does. Writing in place and renaming would be one pass instead of two, but {@code
 * DocumentsContract.renameDocument} is optional for a provider, and a half-written archive left
 * under its final name by a provider that does not support it is the one failure a backup tool
 * must not have. The staging copy also gives {@code createDocument} a file that already exists,
 * which matters because providers silently rename on collision rather than replacing.
 */
public final class SafFolderSink implements BackupSink {

    private static final String MIME_DIR = DocumentsContract.Document.MIME_TYPE_DIR;

    private final ContentResolver resolver;
    private final Uri treeUri;
    private final String label;
    private final File stagingDir;

    /** Document ids resolved this run. Each miss is a cursor query against the provider. */
    private final Map<String, String> dirIds = new HashMap<>();

    public SafFolderSink(Context ctx, Uri treeUri, String label, File stagingDir) {
        this.resolver = ctx.getContentResolver();
        this.treeUri = treeUri;
        this.label = label;
        this.stagingDir = stagingDir;
        if (!stagingDir.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            stagingDir.mkdirs();
        }
    }

    @Override public String describe() {
        return label;
    }

    // ---- document plumbing ----

    private String rootId() {
        return DocumentsContract.getTreeDocumentId(treeUri);
    }

    private Uri docUri(String documentId) {
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
    }

    private Uri childrenUri(String parentId) {
        return DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
    }

    /** The child's document id, or null. {@code dirOnly} filters to directories. */
    private String childId(String parentId, String name, boolean dirOnly) throws IOException {
        try (Cursor c = resolver.query(childrenUri(parentId), new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
        }, null, null, null)) {
            if (c == null) throw new IOException("the folder is no longer readable");
            while (c.moveToNext()) {
                if (!name.equals(c.getString(1))) continue;
                boolean isDir = MIME_DIR.equals(c.getString(2));
                if (dirOnly == isDir) return c.getString(0);
            }
        } catch (SecurityException e) {
            throw new IOException("access to the backup folder was withdrawn", e);
        }
        return null;
    }

    private String ensureDir(String parentId, String name) throws IOException {
        String existing = childId(parentId, name, true);
        if (existing != null) return existing;
        Uri created = DocumentsContract.createDocument(resolver, docUri(parentId), MIME_DIR, name);
        if (created == null) throw new IOException("could not create folder " + name);
        return DocumentsContract.getDocumentId(created);
    }

    private String versionDir(String versionId) throws IOException {
        String id = dirIds.get(versionId);
        if (id == null) {
            id = ensureDir(rootId(), versionId);
            dirIds.put(versionId, id);
        }
        return id;
    }

    /**
     * Creates a child file, replacing any existing one.
     *
     * <p>A provider faced with a name that is taken invents a new one rather than overwriting, so
     * a second backup of the same version would leave {@code manifest (1).json} behind and the
     * original stale. Deleting first is what makes the write a replacement.
     */
    private Uri createFile(String parentId, String name, String mime) throws IOException {
        String existing = childId(parentId, name, false);
        if (existing != null) DocumentsContract.deleteDocument(resolver, docUri(existing));
        Uri created = DocumentsContract.createDocument(resolver, docUri(parentId), mime, name);
        if (created == null) throw new IOException("could not create " + name);
        return created;
    }

    private void copyIn(String parentId, String name, InputStream body) throws IOException {
        Uri file = createFile(parentId, name, DriveApi.mimeFor(name));
        try (OutputStream out = resolver.openOutputStream(file)) {
            if (out == null) throw new IOException("could not open " + name + " for writing");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = body.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    // ---- BackupSink ----

    @Override public void ensureVersion(String versionId) throws IOException {
        versionDir(versionId);
    }

    @Override public OutputStream createArchive(String versionId, String name) throws IOException {
        ensureVersion(versionId);
        return new FileOutputStream(staged(versionId, name));
    }

    @Override public void commitArchive(String versionId, String name) throws IOException {
        File tmp = staged(versionId, name);
        if (!tmp.isFile()) throw new IOException("nothing staged for " + versionId + "/" + name);
        try (InputStream in = new FileInputStream(tmp)) {
            copyIn(versionDir(versionId), name, in);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    @Override public void discardArchive(String versionId, String name) {
        File tmp = staged(versionId, name);
        if (tmp.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    @Override public void writeFile(String versionId, String name, byte[] body) throws IOException {
        copyIn(versionDir(versionId), name, new ByteArrayInputStream(body));
    }

    @Override public InputStream openFile(String versionId, String name) throws IOException {
        String id = childId(versionDir(versionId), name, false);
        if (id == null) throw new IOException("not in the backup folder: " + versionId + "/" + name);
        InputStream in = resolver.openInputStream(docUri(id));
        if (in == null) throw new IOException("could not open " + name);
        return in;
    }

    @Override public boolean hasFile(String versionId, String name) {
        try {
            return childId(versionDir(versionId), name, false) != null;
        } catch (IOException e) {
            return false;
        }
    }

    @Override public List<String> listVersions() throws IOException {
        List<String> out = new ArrayList<>();
        for (String[] row : children(rootId())) {
            if (MIME_DIR.equals(row[2]) && VersionId.looksLikeOne(row[1])) out.add(row[1]);
        }
        java.util.Collections.sort(out);
        return out;
    }

    @Override public List<String> listFiles(String versionId) throws IOException {
        List<String> out = new ArrayList<>();
        for (String[] row : children(versionDir(versionId))) {
            if (!MIME_DIR.equals(row[2])) out.add(row[1]);
        }
        java.util.Collections.sort(out);
        return out;
    }

    @Override public void writeRootFile(String name, byte[] body) throws IOException {
        copyIn(rootId(), name, new ByteArrayInputStream(body));
    }

    @Override public byte[] readRootFile(String name) throws IOException {
        String id = childId(rootId(), name, false);
        if (id == null) throw new IOException("not in the backup folder: " + name);
        try (InputStream in = resolver.openInputStream(docUri(id))) {
            if (in == null) throw new IOException("could not open " + name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    @Override public boolean hasRootFile(String name) {
        try {
            return childId(rootId(), name, false) != null;
        } catch (IOException e) {
            return false;
        }
    }

    @Override public void deleteVersion(String versionId) throws IOException {
        String id = childId(rootId(), versionId, true);
        if (id == null) return;
        // Deleting the directory takes its contents with it on every provider that supports
        // recursive delete, and the children are removed first so that one that does not still
        // ends with an empty folder rather than a half-deleted version.
        for (String[] row : children(id)) {
            DocumentsContract.deleteDocument(resolver, docUri(row[0]));
        }
        DocumentsContract.deleteDocument(resolver, docUri(id));
        dirIds.remove(versionId);
    }

    /** Rows of {documentId, displayName, mimeType}. */
    private List<String[]> children(String parentId) throws IOException {
        List<String[]> out = new ArrayList<>();
        try (Cursor c = resolver.query(childrenUri(parentId), new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
        }, null, null, null)) {
            if (c == null) throw new IOException("the folder is no longer readable");
            while (c.moveToNext()) {
                out.add(new String[] { c.getString(0), c.getString(1), c.getString(2) });
            }
        } catch (SecurityException e) {
            throw new IOException("access to the backup folder was withdrawn", e);
        }
        return out;
    }

    private File staged(String versionId, String name) {
        File dir = new File(stagingDir, versionId);
        if (!dir.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return new File(dir, name + ".part");
    }
}
