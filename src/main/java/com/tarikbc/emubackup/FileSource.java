package com.tarikbc.emubackup;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Reads files under a target root.
 *
 * <p>Two implementations exist: {@link LocalFileSource} using plain {@code java.io.File} for
 * shared storage, and a Shizuku-backed one for {@code Android/data}. Both sit behind this
 * interface so the scan, diff and archive stages never learn which tier they are working on.
 *
 * <p>Android-free by design; see {@code test.sh}.
 */
public interface FileSource {

    /** Whether this source can be used at all right now. */
    boolean available();

    boolean exists(String root);

    /**
     * Lists every <em>file</em> under {@code root}. Directories are not returned: a directory
     * that contains files is implied by their paths and is recreated on restore, and an
     * entirely empty directory carries no save data. See {@code FORMAT.md} section 9.
     *
     * <p>Symbolic links are skipped rather than followed, so a link loop cannot hang a scan.
     *
     * @param recursive when false, only direct children are listed
     * @return target-relative stats, in no guaranteed order
     */
    List<FileStat> walk(String root, boolean recursive) throws IOException;

    /** Opens one file for reading. {@code relPath} is target-relative. */
    InputStream open(String root, String relPath) throws IOException;
}
