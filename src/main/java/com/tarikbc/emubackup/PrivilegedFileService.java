package com.tarikbc.emubackup;

import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The implementation Shizuku loads into a {@code shell}-UID process on our behalf.
 *
 * <p>This is the only code in the app that runs with elevated privilege, which is why it is short
 * and does nothing clever. It performs plain {@code java.io.File} operations on paths the caller
 * names. There is no exec, no shell, no glob evaluation and no path expansion on this side: the
 * registry decides which paths are legitimate, in the unprivileged process, from globs that CI
 * validates. Keeping the decisions out here means the privileged surface is ten methods that can
 * be read in one sitting.
 *
 * <p>Runs in a separate process with no access to the app's own state, so it deliberately depends
 * on nothing from the rest of the codebase.
 */
public final class PrivilegedFileService extends IPrivilegedFiles.Stub {

    private static final int MAX_DEPTH = 64;

    /** Shizuku instantiates this by reflection; both constructor shapes are accepted. */
    public PrivilegedFileService() {}

    public PrivilegedFileService(android.content.Context context) {}

    @Override public int uid() {
        return android.os.Process.myUid();
    }

    @Override public RemoteStat[] list(String root, boolean recursive) {
        File base = new File(root);
        if (!base.isDirectory()) return new RemoteStat[0];

        List<RemoteStat> out = new ArrayList<>();
        Deque<Object[]> stack = new ArrayDeque<>();
        stack.push(new Object[]{base, "", 0});

        while (!stack.isEmpty()) {
            Object[] e = stack.pop();
            File dir = (File) e[0];
            String rel = (String) e[1];
            int depth = (Integer) e[2];

            File[] kids = dir.listFiles();
            if (kids == null) continue;
            for (File f : kids) {
                String childRel = rel.isEmpty() ? f.getName() : rel + "/" + f.getName();
                if (isSymlink(f)) continue;
                if (f.isDirectory()) {
                    if (recursive && depth < MAX_DEPTH) {
                        stack.push(new Object[]{f, childRel, depth + 1});
                    }
                } else if (f.isFile()) {
                    out.add(new RemoteStat(childRel, f.length(), f.lastModified(), false));
                }
            }
        }
        return out.toArray(new RemoteStat[0]);
    }

    @Override public RemoteStat stat(String path) {
        File f = new File(path);
        if (!f.exists()) return null;
        return new RemoteStat(f.getName(), f.length(), f.lastModified(), f.isDirectory());
    }

    @Override public ParcelFileDescriptor openRead(String path) {
        try {
            return ParcelFileDescriptor.open(new File(path), ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (IOException e) {
            return null;
        }
    }

    @Override public ParcelFileDescriptor openWrite(String path) {
        try {
            File f = new File(path);
            File parent = f.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_CREATE
                    | ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_TRUNCATE);
        } catch (IOException e) {
            return null;
        }
    }

    @Override public boolean mkdirs(String path) {
        File f = new File(path);
        return f.isDirectory() || f.mkdirs();
    }

    @Override public boolean renameTo(String from, String to) {
        File dst = new File(to);
        // Rename does not replace on every filesystem, so clear the way first. Safe here because
        // the caller only ever renames a temp file over a path it has already snapshotted.
        if (dst.exists() && !dst.delete()) return false;
        return new File(from).renameTo(dst);
    }

    @Override public boolean deletePath(String path) {
        File f = new File(path);
        return !f.exists() || f.delete();
    }

    @Override public boolean setMtime(String path, long epochMs) {
        return new File(path).setLastModified(epochMs);
    }

    @Override public String sha256(String path) {
        // Hashed in this process so the bytes never cross the binder just to be measured.
        try (FileInputStream in = new FileInputStream(path)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                               .append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    @Override public boolean setMode(String path, int mode) {
        try {
            android.system.Os.chmod(path, mode);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override public void destroy() {
        // Shizuku calls this when unbinding. Ending the process releases the privilege promptly
        // rather than leaving a shell-UID process idling for the lifetime of the device.
        System.exit(0);
    }

    private static boolean isSymlink(File f) {
        try {
            return !f.getCanonicalPath().equals(f.getAbsolutePath());
        } catch (IOException e) {
            return true;
        }
    }
}
