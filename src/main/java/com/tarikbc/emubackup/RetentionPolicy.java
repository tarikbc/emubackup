package com.tarikbc.emubackup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides which versions may be deleted. Pure, and deliberately biased towards keeping.
 *
 * <p>Every rule here exists because getting it wrong destroys data that the person believes is
 * safe, which is worse than any amount of wasted storage. So the order is: work out what must be
 * kept, and only then let the count and the budget take what is left.
 *
 * <p>Three things are never deleted:
 *
 * <ul>
 *   <li><b>The newest version.</b> A store that prunes its way to empty is not a backup.</li>
 *   <li><b>Pinned versions</b>, which is what a pre-restore snapshot is. Those exist precisely
 *       because something was about to be overwritten, and they are the only copy of what was
 *       there before.</li>
 *   <li><b>Any version a kept version's chain extracts from.</b> Deleting the full at the base of
 *       a chain turns every incremental above it into an archive that restores to nothing. The
 *       failure would appear months later, at the one moment the backup was needed.</li>
 * </ul>
 *
 * <p>An entry whose dependencies are unknown, which is every entry written before index version
 * 2, is treated as depending on every older version. That is maximally conservative and it heals
 * itself: once those entries age past the keep count they stop protecting anything, and every
 * entry written since records what it actually needs.
 *
 * <p>Android-free by design; see {@code test.sh}.
 */
public final class RetentionPolicy {

    /**
     * No cap, for either limit. Any negative value means the same.
     *
     * <p>Negative rather than zero on purpose. Zero is a value a person can genuinely set, and a
     * constant that means both "no limit" and "keep none" is the kind of ambiguity that ends with
     * a store deleted by a setting that read as harmless.
     */
    public static final int UNLIMITED = -1;

    private final int keepVersions;
    private final long budgetBytes;

    /**
     * @param keepVersions how many versions to keep, or {@link #UNLIMITED}. The newest is kept
     *                     regardless, so zero is not a way to empty the store.
     * @param budgetBytes  a ceiling on the total size of kept versions, or {@link #UNLIMITED}.
     *                     Applied after the count, oldest first.
     */
    public RetentionPolicy(int keepVersions, long budgetBytes) {
        this.keepVersions = keepVersions;
        this.budgetBytes = budgetBytes;
    }

    /** What a run should delete, oldest first. Never contains a version that must be kept. */
    public List<String> toDelete(List<IndexEntry> versions) {
        List<IndexEntry> byAge = sortedOldestFirst(versions);
        if (byAge.size() <= 1) return new ArrayList<>();

        Set<String> keep = mustKeep(byAge);

        // Candidates, oldest first, are everything not already protected.
        List<IndexEntry> candidates = new ArrayList<>();
        for (IndexEntry e : byAge) if (!keep.contains(e.id)) candidates.add(e);

        Set<String> delete = new LinkedHashSet<>();

        // 1. Over the count, oldest first.
        if (keepVersions >= 0) {
            int over = byAge.size() - keepVersions;
            for (IndexEntry e : candidates) {
                if (over <= 0) break;
                if (delete.add(e.id)) over--;
            }
        }

        // 2. Over the budget, oldest first, counting only what survives step 1.
        if (budgetBytes >= 0) {
            long total = 0;
            for (IndexEntry e : byAge) if (!delete.contains(e.id)) total += e.bytes;
            for (IndexEntry e : candidates) {
                if (total <= budgetBytes) break;
                if (delete.add(e.id)) total -= e.bytes;
            }
        }

        // Deleting one version can free another that only it depended on, but a second pass is
        // not worth the risk of a rule interacting with itself. The next run picks them up.
        return new ArrayList<>(delete);
    }

    /** Ids that must survive, whatever the limits say. */
    private Set<String> mustKeep(List<IndexEntry> byAge) {
        Set<String> keep = new HashSet<>();
        if (byAge.isEmpty()) return keep;

        keep.add(byAge.get(byAge.size() - 1).id);
        for (IndexEntry e : byAge) if (e.pinned) keep.add(e.id);

        Map<String, IndexEntry> byId = new HashMap<>();
        for (IndexEntry e : byAge) byId.put(e.id, e);

        // Anything a protected version needs, transitively. Dependencies only ever point at older
        // versions, so this terminates even on a malformed index.
        List<String> frontier = new ArrayList<>(keep);
        Set<String> seen = new HashSet<>(keep);
        while (!frontier.isEmpty()) {
            List<String> next = new ArrayList<>();
            for (String id : frontier) {
                for (String dep : dependenciesOf(byId.get(id), byAge)) {
                    if (seen.add(dep)) {
                        keep.add(dep);
                        next.add(dep);
                    }
                }
            }
            frontier = next;
        }
        return keep;
    }

    /**
     * What one entry needs. An entry with no recorded dependencies is assumed to need every
     * version older than itself, because the alternative is guessing about restorability.
     */
    private static List<String> dependenciesOf(IndexEntry e, List<IndexEntry> byAge) {
        if (e == null) return new ArrayList<>();
        if (e.depsKnown()) return new ArrayList<>(e.deps);
        List<String> older = new ArrayList<>();
        for (IndexEntry other : byAge) {
            if (other.createdAtMs < e.createdAtMs
                    || (other.createdAtMs == e.createdAtMs && other.id.compareTo(e.id) < 0)) {
                older.add(other.id);
            }
        }
        return older;
    }

    private static List<IndexEntry> sortedOldestFirst(List<IndexEntry> versions) {
        List<IndexEntry> out = new ArrayList<>(versions);
        out.sort((a, b) -> {
            int t = Long.compare(a.createdAtMs, b.createdAtMs);
            // Ids carry a zero-padded counter, so they break a tie in creation order. Two runs
            // inside the same millisecond is not realistic, but a restored index with rounded
            // timestamps is.
            return t != 0 ? t : a.id.compareTo(b.id);
        });
        return Collections.unmodifiableList(out);
    }
}
