package com.tarikbc.emubackup;

import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Tier B writer. Same create-temp-then-commit discipline as the local sink, so an interrupted
 * restore into app-private storage leaves the original save intact.
 *
 * <p>Writing here is the riskiest thing this app does: files created by the {@code shell} user
 * must remain readable by the app that owns the directory. That holds via the path-derived
 * ownership the storage layer applies, but it is unverified on any particular device, which is
 * why Tier B restore stays behind an explicit confirmation until a round trip has been proven.
 */
public final class RemoteFileSink implements FileSink {

    static final String TEMP_SUFFIX = ".ebtmp";

    private final IPrivilegedFiles remote;

    public RemoteFileSink(IPrivilegedFiles remote) {
        this.remote = remote;
    }

    @Override public boolean available() {
        return remote != null && remote.asBinder() != null && remote.asBinder().pingBinder();
    }

    @Override public void mkdirs(String root, String relPath) throws IOException {
        String full = PathResolver.join(root, relPath);
        int slash = full.lastIndexOf('/');
        if (slash <= 0) return;
        try {
            if (!remote.mkdirs(full.substring(0, slash))) {
                throw new IOException("could not create parent of " + full);
            }
        } catch (RemoteException e) {
            throw new IOException("privileged mkdirs failed for " + full, e);
        }
    }

    @Override public OutputStream createTemp(String root, String relPath) throws IOException {
        mkdirs(root, relPath);
        String tmp = PathResolver.join(root, relPath) + TEMP_SUFFIX;
        try {
            ParcelFileDescriptor pfd = remote.openWrite(tmp);
            if (pfd == null) throw new IOException("could not create " + tmp);
            return new ParcelFileDescriptor.AutoCloseOutputStream(pfd);
        } catch (RemoteException e) {
            throw new IOException("privileged create failed for " + tmp, e);
        }
    }

    @Override public void commit(String root, String relPath) throws IOException {
        String dst = PathResolver.join(root, relPath);
        try {
            if (!remote.renameTo(dst + TEMP_SUFFIX, dst)) {
                throw new IOException("could not commit " + dst);
            }
        } catch (RemoteException e) {
            throw new IOException("privileged commit failed for " + dst, e);
        }
    }

    @Override public void discardTemp(String root, String relPath) {
        try {
            remote.deletePath(PathResolver.join(root, relPath) + TEMP_SUFFIX);
        } catch (RemoteException ignored) {
            // A stray temp file is harmless and is cleaned up on the next attempt.
        }
    }

    @Override public void setMtime(String root, String relPath, long epochMs) throws IOException {
        String full = PathResolver.join(root, relPath);
        try {
            if (!remote.setMtime(full, epochMs)) throw new IOException("could not set mtime on " + full);
        } catch (RemoteException e) {
            throw new IOException("privileged setMtime failed for " + full, e);
        }
    }

    @Override public void delete(String root, String relPath) throws IOException {
        String full = PathResolver.join(root, relPath);
        try {
            if (!remote.deletePath(full)) throw new IOException("could not delete " + full);
        } catch (RemoteException e) {
            throw new IOException("privileged delete failed for " + full, e);
        }
    }
}
