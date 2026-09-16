package com.tarikbc.emubackup;

import android.content.Context;
import android.os.Environment;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One pass over the whole registry: load it, work out what this device can currently reach,
 * scan every target, and hold the results.
 *
 * <p>Blocking. Callers run it on a background executor and post the result back, following the
 * concurrency contract in {@code ARCHITECTURE.md} section 8.
 */
public final class ScanSession {

    public final TargetRegistry registry;
    public final Capabilities caps;
    public final List<TargetScan> scans;
    public final String registryError;

    /** Why app-private storage is or is not reachable. Never null. */
    public final ShizukuGate.Status shizuku;

    private final Map<String, TargetScan> byTarget = new LinkedHashMap<>();

    private ScanSession(TargetRegistry registry, Capabilities caps, List<TargetScan> scans,
                        String registryError, ShizukuGate.Status shizuku) {
        this.registry = registry;
        this.caps = caps;
        this.scans = scans;
        this.registryError = registryError;
        this.shizuku = shizuku;
        for (TargetScan s : scans) byTarget.put(s.targetId, s);
    }

    public static ScanSession run(Context ctx) {
        // Blocking, and deliberately attempted on every scan: Shizuku does not survive a reboot,
        // so a cached "you are covered" would be exactly the stale reassurance to avoid.
        ShizukuGate.Status shizuku = ShizukuGate.connect(ctx);
        Capabilities caps = new Capabilities(Permissions.hasAllFiles(), shizuku.ready(),
                DriveClient.of(ctx).configured());

        TargetRegistry reg;
        try {
            reg = TargetRegistry.parseWithOverride(
                    Assets.readString(ctx, "targets.json"), readOverride());
        } catch (Exception e) {
            // A registry that will not load means the app would scan nothing at all. That is
            // the one failure that must never present as "no saves found".
            return new ScanSession(null, caps, new ArrayList<>(), describe(e), shizuku);
        }

        PathResolver resolver = new PathResolver(Environment.getExternalStorageDirectory().getAbsolutePath());
        FileSource shared = new LocalFileSource();
        FileSource appPrivate = shizuku.ready()
                ? new RemoteFileSource(ShizukuGate.service()) : null;
        ScanEngine engine = new ScanEngine(resolver, shared, appPrivate, caps, new AppInfo(ctx));

        List<TargetScan> out = new ArrayList<>();
        for (Emulator e : reg.emulators()) {
            out.addAll(engine.scanAll(e, e.targets));
        }
        return new ScanSession(reg, caps, out, null, shizuku);
    }

    /**
     * A user-supplied registry override, if present. Emulators move their save paths without
     * warning, and waiting for a release is the wrong answer when data is already at risk.
     */
    private static String readOverride() {
        try {
            File f = new File(Environment.getExternalStorageDirectory(), "EmuBackup/targets.local.json");
            if (!f.isFile() || f.length() > 1_000_000) return null;
            byte[] b = new byte[(int) f.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int n = 0;
                while (n < b.length) {
                    int r = in.read(b, n, b.length - n);
                    if (r < 0) break;
                    n += r;
                }
            }
            return new String(b, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static String describe(Exception e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    public boolean ok() {
        return registryError == null;
    }

    public TargetScan scanOf(String targetId) {
        return byTarget.get(targetId);
    }

    public long bytesOf(Category category) {
        long n = 0;
        for (TargetScan s : scans) {
            if (!s.hasContent()) continue;
            if (registry.target(s.targetId).category == category) n += s.totalBytes;
        }
        return n;
    }

    /** Bytes found but not selected by default, which is what the skipped segment reports. */
    public long skippedBytes() {
        long n = 0;
        for (TargetScan s : scans) {
            if (!s.hasContent()) continue;
            if (!registry.target(s.targetId).enabledByDefault) n += s.totalBytes;
        }
        return n;
    }

    public long coveredBytes() {
        long n = 0;
        for (TargetScan s : scans) {
            if (!s.hasContent()) continue;
            if (registry.target(s.targetId).enabledByDefault) n += s.totalBytes;
        }
        return n;
    }

    public int withContent() {
        int n = 0;
        for (TargetScan s : scans) if (s.hasContent()) n++;
        return n;
    }

    public int locked() {
        return ScanEngine.lockedCount(scans);
    }

    public List<TargetScan> problems() {
        return ScanEngine.problems(scans);
    }
}
