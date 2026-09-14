package com.tarikbc.emubackup;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Where backups are written.
 *
 * <p>Two implementations produce a byte-identical tree: a local folder and, later, Google Drive.
 * The local one is not a fallback bolted on for convenience — it is the guarantee that a backup
 * survives this app, and it is what the entire test suite exercises, so the backup and restore
 * pipeline is verified with no network at all.
 *
 * <p>Android-free by design; see {@code test.sh}.
 */
public interface BackupSink {

    /** Human-readable description of where this writes, shown in the UI. */
    String describe();

    void ensureVersion(String versionId) throws IOException;

    /** Opens an archive for streaming. Nothing is visible under its final name until closed. */
    OutputStream createArchive(String versionId, String name) throws IOException;

    /** Finalises an archive opened by {@link #createArchive}. */
    void commitArchive(String versionId, String name) throws IOException;

    /** Removes a partially written archive. Never throws for absence. */
    void discardArchive(String versionId, String name);

    void writeFile(String versionId, String name, byte[] body) throws IOException;

    InputStream openFile(String versionId, String name) throws IOException;

    boolean hasFile(String versionId, String name);

    /** Version directory ids, in no guaranteed order. */
    List<String> listVersions() throws IOException;

    List<String> listFiles(String versionId) throws IOException;

    /** Files at the root of the backup store, such as {@code index.json}. */
    void writeRootFile(String name, byte[] body) throws IOException;

    byte[] readRootFile(String name) throws IOException;

    boolean hasRootFile(String name);

    void deleteVersion(String versionId) throws IOException;
}
