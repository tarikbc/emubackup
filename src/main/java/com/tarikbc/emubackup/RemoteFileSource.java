package com.tarikbc.emubackup;

import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Tier B reader: the same {@link FileSource} contract, served by the privileged process.
 *
 * <p>Nothing above this class learns which tier it is working on. {@code ScanEngine},
 * {@code DiffEngine} and both runners see one interface, which is what let the whole backup and
 * restore pipeline be written and tested before Shizuku existed in this codebase.
 */
public final class RemoteFileSource implements FileSource {

    private final IPrivilegedFiles remote;

    public RemoteFileSource(IPrivilegedFiles remote) {
        this.remote = remote;
    }

    @Override public boolean available() {
        return remote != null && remote.asBinder() != null && remote.asBinder().pingBinder();
    }

    @Override public boolean exists(String root) {
        try {
            RemoteStat s = remote.stat(root);
            return s != null && s.dir;
        } catch (RemoteException e) {
            return false;
        }
    }

    @Override public List<FileStat> walk(String root, boolean recursive) throws IOException {
        try {
            RemoteStat[] stats = remote.list(root, recursive);
            List<FileStat> out = new ArrayList<>(stats == null ? 0 : stats.length);
            if (stats != null) {
                for (RemoteStat s : stats) out.add(new FileStat(s.path, s.size, s.mtimeMs));
            }
            return out;
        } catch (RemoteException e) {
            throw new IOException("privileged listing failed for " + root, e);
        }
    }

    @Override public InputStream open(String root, String relPath) throws IOException {
        String full = PathResolver.join(root, relPath);
        try {
            ParcelFileDescriptor pfd = remote.openRead(full);
            if (pfd == null) throw new IOException("could not open " + full);
            // AutoCloseInputStream closes the descriptor with the stream, so a caller that uses
            // try-with-resources does not leak a file descriptor into the privileged process.
            return new ParcelFileDescriptor.AutoCloseInputStream(pfd);
        } catch (RemoteException e) {
            throw new IOException("privileged open failed for " + full, e);
        }
    }
}
