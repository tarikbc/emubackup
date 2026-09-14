package com.tarikbc.emubackup;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Walks a target's root, applies its globs, and classifies the outcome.
 *
 * <p>Every decision that keeps a backup honest happens here: a target over its cap is refused
 * outright rather than truncated, an unreadable directory is an error rather than an empty
 * result, and the count of files that did <em>not</em> match is recorded so a wrong glob is
 * visible rather than silent.
 *
 * <p>Android-free by design; see {@code test.sh}.
 */
public final class ScanEngine {

    private final PathResolver resolver;
    private final FileSource shared;
    private final FileSource appPrivate;
    private final Capabilities caps;
    private final PackagePresence presence;

    /**
     * @param appPrivate may be null when Shizuku is unavailable; app-private targets then
     *                   report {@link TargetStatus#TIER_UNAVAILABLE} rather than failing
     */
    public ScanEngine(PathResolver resolver, FileSource shared, FileSource appPrivate,
                      Capabilities caps, PackagePresence presence) {
        if (resolver == null || shared == null || caps == null || presence == null) {
            throw new IllegalArgumentException("resolver, shared source, capabilities and presence are required");
        }
        this.resolver = resolver;
        this.shared = shared;
        this.appPrivate = appPrivate;
        this.caps = caps;
        this.presence = presence;
    }

    public List<TargetScan> scanAll(Emulator e, Collection<Target> targets) {
        List<TargetScan> out = new ArrayList<>(targets.size());
        for (Target t : targets) out.add(scan(e, t));
        return out;
    }

    public TargetScan scan(Emulator emulator, Target t) {
        // An emulator may ship under more than one package name — Eden has both a nightly and
        // a stable build — so resolve {DATA} against whichever is actually installed rather
        // than whichever the registry happened to name first.
        String pkg = t.pkg;
        boolean installed = true;
        if (t.tier == Tier.APP_PRIVATE) {
            String found = firstInstalled(emulator);
            installed = found != null;
            if (found != null) pkg = found;
            if (!installed) {
                return fail(t, TargetStatus.PKG_NOT_INSTALLED, null,
                        "none of " + emulator.packages + " is installed");
            }
        }

        if (!caps.canRead(t.tier)) {
            return fail(t, TargetStatus.TIER_UNAVAILABLE, null,
                    t.tier == Tier.APP_PRIVATE
                            ? "app-private storage needs Shizuku"
                            : "all-files access has not been granted");
        }

        String root;
        try {
            root = resolver.resolve(t.root, pkg);
        } catch (IllegalArgumentException ex) {
            return fail(t, TargetStatus.UNREADABLE, null, "bad root: " + ex.getMessage());
        }

        FileSource src = t.tier == Tier.SHARED ? shared : appPrivate;
        if (src == null || !src.available()) {
            return fail(t, TargetStatus.TIER_UNAVAILABLE, root, "no reader available for this tier");
        }
        if (!src.exists(root)) {
            return fail(t, TargetStatus.ROOT_MISSING, root, null);
        }

        List<FileStat> all;
        try {
            all = src.walk(root, t.recursive);
        } catch (IOException ex) {
            return fail(t, TargetStatus.UNREADABLE, root, ex.getMessage());
        }

        PathMatcher matcher = t.matcher();
        List<FileStat> matched = new ArrayList<>();
        List<String> unmatchedSample = new ArrayList<>();
        int unmatched = 0;
        long bytes = 0;
        for (FileStat f : all) {
            if (matcher.matches(f.path)) {
                matched.add(f);
                bytes += f.size;
            } else {
                unmatched++;
                if (unmatchedSample.size() < TargetScan.UNMATCHED_SAMPLE_CAP) unmatchedSample.add(f.path);
            }
        }

        if (bytes > t.maxBytes) {
            // Refused, not trimmed. A partial backup that presents itself as complete is worse
            // than a visible refusal, and a cap this far out usually means a glob is wrong.
            return new TargetScan(t.id, TargetStatus.OVER_CAP, root, new ArrayList<>(), bytes,
                    unmatched, unmatchedSample,
                    Sizes.human(bytes) + " exceeds the " + Sizes.human(t.maxBytes) + " cap for this target");
        }

        TargetStatus status = matched.isEmpty() ? TargetStatus.EMPTY : TargetStatus.OK;
        return new TargetScan(t.id, status, root, matched, bytes, unmatched, unmatchedSample, null);
    }

    private String firstInstalled(Emulator e) {
        for (String p : e.packages) if (presence.isInstalled(p)) return p;
        return null;
    }

    private static TargetScan fail(Target t, TargetStatus status, String root, String detail) {
        return new TargetScan(t.id, status, root, new ArrayList<>(), 0, 0, new ArrayList<>(), detail);
    }

    // ------------------------------------------------------------- aggregates

    /** Total bytes across scans that actually produced content. */
    public static long totalBytes(Collection<TargetScan> scans) {
        long n = 0;
        for (TargetScan s : scans) if (s.hasContent()) n += s.totalBytes;
        return n;
    }

    public static int totalFiles(Collection<TargetScan> scans) {
        int n = 0;
        for (TargetScan s : scans) if (s.hasContent()) n += s.fileCount();
        return n;
    }

    /** How many targets are held back purely for want of Shizuku. Drives the upsell copy. */
    public static int lockedCount(Collection<TargetScan> scans) {
        int n = 0;
        for (TargetScan s : scans) if (s.status == TargetStatus.TIER_UNAVAILABLE) n++;
        return n;
    }

    public static List<TargetScan> problems(Collection<TargetScan> scans) {
        List<TargetScan> out = new ArrayList<>();
        for (TargetScan s : scans) if (s.status.isProblem()) out.add(s);
        return out;
    }
}
