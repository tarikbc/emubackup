package com.tarikbc.emubackup;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Writes files under a target root, used by restore.
 *
 * <p>The write discipline is create-temp then commit, never write-in-place. A restore killed
 * mid-file must leave the original save intact and at worst a stray temp file, because the
 * one thing a backup tool must never do is destroy the data it exists to protect.
 *
 * <p>Android-free by design; see {@code test.sh}.
 */
public interface FileSink {

    boolean available();

    /** Creates {@code relPath}'s parent directories. */
    void mkdirs(String root, String relPath) throws IOException;

    /**
     * Opens a temporary sibling of {@code relPath} for writing. Nothing is visible at the
     * final path until {@link #commit} succeeds.
     */
    OutputStream createTemp(String root, String relPath) throws IOException;

    /** Atomically moves the temporary file written by {@link #createTemp} into place. */
    void commit(String root, String relPath) throws IOException;

    /** Discards a temporary file for a write that was abandoned. Never throws for absence. */
    void discardTemp(String root, String relPath);

    void setMtime(String root, String relPath, long epochMs) throws IOException;

    void delete(String root, String relPath) throws IOException;
}
