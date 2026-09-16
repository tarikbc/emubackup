package com.tarikbc.emubackup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** One line in the store index. See {@code FORMAT.md} section 8. */
public final class IndexEntry {

    public final String id;
    public final long createdAtMs;
    public final long bytes;
    public final boolean pinned;

    /** {@code "manual"}, {@code "scheduled"} or {@code "prerestore"}. */
    public final String kind;

    /**
     * Version ids whose archives this version's chains extract from, excluding itself.
     *
     * <p>Null means unknown, which is what every entry written before index version 2 is.
     * {@link RetentionPolicy} treats unknown as "depends on everything older", because deleting
     * the base of a chain leaves an incremental that cannot be restored, and a backup that
     * silently cannot be restored is the worst thing this app could produce.
     *
     * <p>Recording it here rather than reading it back from the manifests is what keeps pruning
     * cheap: on a Drive store, opening twenty manifests to work out what is safe to delete would
     * be several megabytes of downloads after every single run.
     */
    public final List<String> deps;

    public IndexEntry(String id, long createdAtMs, long bytes, boolean pinned, String kind) {
        this(id, createdAtMs, bytes, pinned, kind, null);
    }

    public IndexEntry(String id, long createdAtMs, long bytes, boolean pinned, String kind,
                      List<String> deps) {
        this.id = id;
        this.createdAtMs = createdAtMs;
        this.bytes = bytes;
        this.pinned = pinned;
        this.kind = kind;
        this.deps = deps == null ? null
                : Collections.unmodifiableList(new ArrayList<>(new LinkedHashSet<>(deps)));
    }

    public boolean isPreRestore() {
        return "prerestore".equals(kind);
    }

    public boolean depsKnown() {
        return deps != null;
    }

    /**
     * The versions a manifest's chains extract from, excluding the manifest's own version.
     *
     * <p>Chain entries are {@code "<version>/<archive>"}, so the version is everything before the
     * first slash. A full archive's chain contains only itself and yields nothing.
     */
    public static List<String> dependenciesOf(Manifest m) {
        Set<String> out = new LinkedHashSet<>();
        for (ManifestTarget t : m.targets) {
            for (String link : t.chain) {
                int slash = link.indexOf('/');
                if (slash <= 0) continue;
                String v = link.substring(0, slash);
                if (!v.equals(m.version)) out.add(v);
            }
        }
        return new ArrayList<>(out);
    }
}
