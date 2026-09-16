package com.tarikbc.emubackup;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import rikka.shizuku.Shizuku;

/**
 * Everything to do with obtaining, and proving, privileged file access.
 *
 * <p>Shizuku is optional. Tier A covers about 700 MB of the 730 MB on the reference device and
 * works on first launch with no Shizuku at all, so nothing here may ever block the app. Every
 * failure resolves to a {@link State} with something specific to tell the user.
 *
 * <p>The probe matters as much as the binding. A privileged path that silently returns nothing
 * produces an empty backup that looks successful, which is precisely the failure this app exists
 * to prevent, so access is never assumed from a successful bind alone.
 */
public final class ShizukuGate {

    public static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
    public static final int PERMISSION_REQUEST = 4001;

    /** Bumped when the privileged interface changes, so Shizuku restarts a stale service. */
    private static final int SERVICE_VERSION = 1;

    private static final long BIND_TIMEOUT_MS = 10_000;

    public enum State {
        /** Shizuku is not installed. */
        NOT_INSTALLED,
        /** Installed but the service is not running; it does not survive a reboot. */
        NOT_RUNNING,
        /** Running, but this app has not been granted access. */
        NEEDS_PERMISSION,
        /** Granted, but the privileged service could not be started or did not work. */
        PROBE_FAILED,
        /** Working. */
        READY
    }

    public static final class Status {
        public final State state;
        public final int uid;
        public final int serverVersion;
        public final String detail;

        Status(State state, int uid, int serverVersion, String detail) {
            this.state = state;
            this.uid = uid;
            this.serverVersion = serverVersion;
            this.detail = detail;
        }

        public boolean ready() {
            return state == State.READY;
        }

        /** {@code shell} is the normal case; {@code root} appears when Shizuku runs under Magisk. */
        public String identity() {
            return uid == 0 ? "root" : uid == 2000 ? "shell" : ("uid " + uid);
        }
    }

    private static volatile IPrivilegedFiles service;
    private static volatile ServiceConnection connection;

    private ShizukuGate() {}

    public static boolean isInstalled(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(SHIZUKU_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** The current state without attempting to bind. Cheap; safe on the main thread. */
    public static State quickState(Context ctx) {
        if (service != null && service.asBinder() != null && service.asBinder().pingBinder()) {
            return State.READY;
        }
        if (!pingSafely()) return isInstalled(ctx) ? State.NOT_RUNNING : State.NOT_INSTALLED;
        return hasPermission() ? State.NOT_RUNNING : State.NEEDS_PERMISSION;
    }

    public static boolean hasPermission() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void requestPermission() {
        try {
            Shizuku.requestPermission(PERMISSION_REQUEST);
        } catch (Throwable ignored) {
            // Only reachable if the binder died between the check and the request.
        }
    }

    /**
     * Binds the privileged service and proves it works. Blocking; call from a background thread.
     *
     * <p>Idempotent: a live service is reused rather than rebound.
     */
    public static Status connect(Context ctx) {
        if (!isInstalled(ctx)) {
            return new Status(State.NOT_INSTALLED, -1, -1, "Shizuku is not installed");
        }
        if (!pingSafely()) {
            return new Status(State.NOT_RUNNING, -1, -1,
                    "Shizuku is installed but not running. It has to be started again after every reboot.");
        }
        if (!hasPermission()) {
            return new Status(State.NEEDS_PERMISSION, -1, serverVersion(),
                    "Shizuku is running but has not granted access to EmuBackup");
        }

        IPrivilegedFiles existing = service;
        if (existing != null && existing.asBinder() != null && existing.asBinder().pingBinder()) {
            return probe(existing);
        }

        final CountDownLatch latch = new CountDownLatch(1);
        ServiceConnection conn = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                service = IPrivilegedFiles.Stub.asInterface(binder);
                latch.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                service = null;
            }
        };

        try {
            Shizuku.bindUserService(userServiceArgs(ctx), conn);
        } catch (Throwable t) {
            return new Status(State.PROBE_FAILED, -1, serverVersion(),
                    "Could not start the privileged service: " + describe(t));
        }

        try {
            if (!latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return new Status(State.PROBE_FAILED, -1, serverVersion(),
                        "The privileged service did not start within " + (BIND_TIMEOUT_MS / 1000) + " seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Status(State.PROBE_FAILED, -1, serverVersion(), "Interrupted while connecting");
        }

        connection = conn;
        return probe(service);
    }

    /**
     * Confirms the service is genuinely privileged and can see app-private storage.
     *
     * <p>Two checks rather than one. A service that starts but runs as the app's own uid, or one
     * that runs privileged but cannot read the directory in question, both produce empty backups
     * that report success.
     */
    private static Status probe(IPrivilegedFiles s) {
        if (s == null) {
            return new Status(State.PROBE_FAILED, -1, serverVersion(), "No privileged service is bound");
        }
        int uid;
        try {
            uid = s.uid();
        } catch (Throwable t) {
            return new Status(State.PROBE_FAILED, -1, serverVersion(),
                    "The privileged service is not responding: " + describe(t));
        }
        if (uid != 0 && uid != 2000) {
            return new Status(State.PROBE_FAILED, uid, serverVersion(),
                    "The service started as uid " + uid + ", which cannot read app-private storage");
        }
        try {
            // Reading the one directory every Android build has proves the privilege is real,
            // without depending on any particular emulator being installed.
            RemoteStat probe = s.stat("/storage/emulated/0/Android/data");
            if (probe == null || !probe.dir) {
                return new Status(State.PROBE_FAILED, uid, serverVersion(),
                        "Running as " + (uid == 0 ? "root" : "shell")
                                + " but Android/data is still not readable on this device");
            }
        } catch (Throwable t) {
            return new Status(State.PROBE_FAILED, uid, serverVersion(),
                    "Privileged read failed: " + describe(t));
        }
        return new Status(State.READY, uid, serverVersion(), null);
    }

    public static IPrivilegedFiles service() {
        IPrivilegedFiles s = service;
        return s != null && s.asBinder() != null && s.asBinder().pingBinder() ? s : null;
    }

    public static void disconnect(Context ctx) {
        ServiceConnection c = connection;
        if (c == null) return;
        try {
            Shizuku.unbindUserService(userServiceArgs(ctx), c, true);
        } catch (Throwable ignored) {
        }
        connection = null;
        service = null;
    }

    private static Shizuku.UserServiceArgs userServiceArgs(Context ctx) {
        return new Shizuku.UserServiceArgs(
                new ComponentName(ctx.getPackageName(), PrivilegedFileService.class.getName()))
                .daemon(false)
                .processNameSuffix("privileged")
                .debuggable(false)
                .version(SERVICE_VERSION);
    }

    private static boolean pingSafely() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    private static int serverVersion() {
        try {
            return Shizuku.getVersion();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String describe(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }
}
