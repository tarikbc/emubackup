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

    /**
     * What happened to {@code EmuBackup/targets.local.json}. Never null.
     *
     * <p>An override exists so that an emulator moving its save path can be fixed today rather
     * than at the next release. That only helps if a broken one is loud: someone edits the file
     * precisely because their saves are already at risk, and an app that silently falls back to
     * the bundled paths would go on backing up the wrong folder while they believe they fixed
     * it. Absent is fine and silent; present-but-unusable is not.
     */
    public final String overrideStatus;

    private final Map<String, TargetScan> byTarget = new LinkedHashMap<>();

    /**
     * Per-category totals from the most recent scan in this process.
     *
     * <p>So a screen can quote a size without rescanning. Deliberately not persisted: a stale
     * number that survives a reboot is worse than no number, and every screen that shows one is
     * one tap from the scan that would produce a fresh one.
     */
    private static final Map<Category, Long> LAST_BYTES =
            java.util.Collections.synchronizedMap(new java.util.EnumMap<>(Category.class));

    private ScanSession(TargetRegistry registry, Capabilities caps, List<TargetScan> scans,
                        String registryError, ShizukuGate.Status shizuku, String overrideStatus) {
        this.registry = registry;
        this.caps = caps;
        this.scans = scans;
        this.registryError = registryError;
        this.shizuku = shizuku;
        this.overrideStatus = overrideStatus == null ? "none" : overrideStatus;
        for (TargetScan s : scans) byTarget.put(s.targetId, s);
    }

    public static ScanSession run(Context ctx) {
        // Blocking, and deliberately attempted on every scan: Shizuku does not survive a reboot,
        // so a cached "you are covered" would be exactly the stale reassurance to avoid.
        ShizukuGate.Status shizuku = ShizukuGate.connect(ctx);
        Capabilities caps = new Capabilities(Permissions.hasAllFiles(), shizuku.ready(),
                DriveClient.of(ctx).configured());

        Override ov = readOverride();
        TargetRegistry reg;
        try {
            reg = TargetRegistry.parseWithOverride(
                    Assets.readString(ctx, "targets.json"), ov.json);
        } catch (Exception e) {
            // A registry that will not load means the app would scan nothing at all. That is
            // the one failure that must never present as "no saves found".
            // An override that parses as JSON but breaks the registry lands here, and the
            // message is about the registry as a whole. Naming the override too is what tells
            // someone which file to go and fix.
            String why = describe(e) + (ov.json == null ? ""
                    : "  (an override at " + OVERRIDE_PATH + " is being applied)");
            return new ScanSession(null, caps, new ArrayList<>(), why, shizuku, ov.status);
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
        ScanSession session = new ScanSession(reg, caps, out, null, shizuku, ov.status);
        for (Category c : Category.values()) LAST_BYTES.put(c, session.bytesOf(c));
        return session;
    }

    /** What the last scan found for a category, or 0 when nothing has been scanned yet. */
    public static long lastKnownBytes(Category category) {
        Long v = LAST_BYTES.get(category);
        return v == null ? 0L : v;
    }

    /**
     * A user-supplied registry override, if present. Emulators move their save paths without
     * warning, and waiting for a release is the wrong answer when data is already at risk.
     */
    static final String OVERRIDE_PATH = "EmuBackup/targets.local.json";

    /** The override file's contents, and a one-line account of what happened to it. */
    private static final class Override {
        final String json;
        final String status;

        Override(String json, String status) {
            this.json = json;
            this.status = status;
        }
    }

    private static Override readOverride() {
        File f = new File(Environment.getExternalStorageDirectory(), OVERRIDE_PATH);
        if (!f.isFile()) return new Override(null, "none");

        // A cap, because this is parsed before anything else and a huge file would stall the
        // scan. Reported rather than ignored: the file is there, so someone meant it.
        if (f.length() > 1_000_000) {
            return new Override(null, "IGNORED, larger than 1 MB: " + OVERRIDE_PATH);
        }
        try {
            byte[] b = new byte[(int) f.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int n = 0;
                while (n < b.length) {
                    int r = in.read(b, n, b.length - n);
                    if (r < 0) break;
                    n += r;
                }
            }
            String json = new String(b, java.nio.charset.StandardCharsets.UTF_8);
            return new Override(json, "applied, sha256 " + Hashes.sha256(b).substring(0, 12));
        } catch (Exception e) {
            return new Override(null, "UNREADABLE, so it is not being applied: " + describe(e));
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
