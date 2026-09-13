package com.tarikbc.emubackup;

import com.tarikbc.emubackup.RemoteStat;

/**
 * File operations performed at shell privilege inside a process Shizuku starts for us.
 *
 * Deliberately narrow: it exposes only what FileSource and FileSink need, so the
 * privileged surface can be audited in one short file. There is no exec, no arbitrary
 * command, and no path-glob evaluation on this side of the boundary — the caller
 * decides which paths to touch, and the registry decides that from validated globs.
 *
 * Transaction ids are assigned explicitly because Shizuku calls destroy() at its own
 * reserved code (16777114), and AIDL requires ids on all methods or none. Never
 * renumber an existing method: bump UserServiceArgs.version() instead, or an old
 * running service will answer the wrong call.
 *
 * See docs/ARCHITECTURE.md "Two-tier storage".
 */
interface IPrivilegedFiles {

    /** 2000 for shell, 0 for root. Used by the capability probe. */
    int uid() = 1;

    /** Recursive or direct-children listing of a directory. Empty array if absent. */
    RemoteStat[] list(String root, boolean recursive) = 2;

    /** Single-path stat, or null if absent. */
    RemoteStat stat(String path) = 3;

    ParcelFileDescriptor openRead(String path) = 4;
    ParcelFileDescriptor openWrite(String path) = 5;

    boolean mkdirs(String path) = 6;
    boolean renameTo(String from, String to) = 7;
    boolean deletePath(String path) = 8;
    boolean setMtime(String path, long epochMs) = 9;

    /** Hex SHA-256, computed in-process so the bytes never cross the binder. */
    String sha256(String path) = 10;

    /** Called by Shizuku when the service is unbound. */
    void destroy() = 16777114;
}
