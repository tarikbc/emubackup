package com.tarikbc.emubackup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per game: when it was backed up, in which backups it changed, and whether it has changed
 * since. This is what turns a store of versions into "Zelda, Tue 16 Sep" on a screen.
 *
 * <p>Built by walking the manifests oldest to newest and comparing each game group's files by
 * <b>content hash</b>, never by the {@code version} stamp. A full rebase restamps every file to
 * the new version (see {@code DiffEngine.asFull}), so stamps would show every game as changed
 * on those versions when nothing was.
 *
 * <p>Two honesty rules. A target that is absent from a manifest, or empty with a problem
 * status, was <em>not observed</em> in that backup, which is different from its game having
 * been deleted; it is skipped, not counted as a change. Safety copies made before a restore
 * hold only the files that were about to be overwritten, so they are kept out of the change
 * chain and listed separately.
 *
 * <p>Android-free by design; see {@code test.sh}.
 */
public final class GameHistory {

    /** One backup in which the game changed. */
    public static final class Snapshot {
        public final String versionId;
        public final long atMs;
        public final int filesChanged;
        /** True for the first backup that ever contained this game. */
        public final boolean first;

        Snapshot(String versionId, long atMs, int filesChanged, boolean first) {
            this.versionId = versionId;
            this.atMs = atMs;
            this.filesChanged = filesChanged;
            this.first = first;
        }
    }

    public static final class Entry {
        public final String targetId;
        public final String key;
        /** The group as it stands now on the device, or as last backed up when not on the device. */
        public final SaveGroup group;
        /** Newest first. */
        public final List<Snapshot> snapshots;
        /** Versions of safety copies (pre-restore) that hold this game, newest first. */
        public final List<String> safetyCopies;
        /** 0 when never backed up. */
        public final long lastBackedUpMs;
        /** True when the device's copy differs from the last backup, or was never backed up. */
        public final boolean changedSinceBackup;
        /** True when the game is on the device now. */
        public final boolean onDevice;

        Entry(String targetId, String key, SaveGroup group, List<Snapshot> snapshots,
              List<String> safetyCopies, long lastBackedUpMs, boolean changedSinceBackup,
              boolean onDevice) {
            this.targetId = targetId;
            this.key = key;
            this.group = group;
            this.snapshots = Collections.unmodifiableList(snapshots);
            this.safetyCopies = Collections.unmodifiableList(safetyCopies);
            this.lastBackedUpMs = lastBackedUpMs;
            this.changedSinceBackup = changedSinceBackup;
            this.onDevice = onDevice;
        }

        /** How long since the current files changed, so a list can sort by "played recently". */
        public long newestMtimeMs() {
            return group.newestMtimeMs;
        }
    }

    private GameHistory() {}

    /** A stable identity across targets: {@code targetId + "|" + group.key()}. */
    public static String keyOf(String targetId, SaveGroup g) {
        return targetId + "|" + g.key();
    }

    /**
     * @param registry  for each target's grouping rule
     * @param index     every version in the store; order and kind are taken from here
     * @param manifests by version id; a version missing here is treated as unobserved
     * @param scan      the current device scan, or empty when only the store is known
     */
    public static Map<String, Entry> build(TargetRegistry registry, List<IndexEntry> index,
                                           Map<String, Manifest> manifests,
                                           List<TargetScan> scan) {
        List<IndexEntry> ordered = new ArrayList<>(index);
        ordered.sort((a, b) -> {
            int c = Long.compare(a.createdAtMs, b.createdAtMs);
            return c != 0 ? c : a.id.compareTo(b.id);
        });

        // Per group key: the path -> sha256 map as of the last backup that observed it.
        Map<String, Map<String, String>> lastSeen = new HashMap<>();
        Map<String, String> lastTarget = new HashMap<>();
        Map<String, SaveGroup> lastGroup = new HashMap<>();
        Map<String, Long> lastBackedUp = new HashMap<>();
        Map<String, List<Snapshot>> snaps = new LinkedHashMap<>();
        Map<String, List<String>> safety = new HashMap<>();

        for (IndexEntry e : ordered) {
            Manifest m = manifests.get(e.id);
            if (m == null) continue;

            if (e.isPreRestore()) {
                for (Map.Entry<String, SaveGroup> g : groupsOf(registry, m).entrySet()) {
                    safety.computeIfAbsent(g.getKey(), k -> new ArrayList<>()).add(e.id);
                }
                continue;
            }

            // Which targets this backup actually looked at. Absent or problem-status targets
            // say nothing about their games.
            Map<String, SaveGroup> now = groupsOf(registry, m);
            java.util.Set<String> observedTargets = new java.util.HashSet<>();
            for (ManifestTarget t : m.targets) {
                if (t.status == TargetStatus.OK || t.status == TargetStatus.EMPTY) {
                    observedTargets.add(t.id);
                }
            }

            // Changed or new groups.
            for (Map.Entry<String, SaveGroup> g : now.entrySet()) {
                String key = g.getKey();
                Map<String, String> hashes = hashesOf(m, g.getValue());
                Map<String, String> before = lastSeen.get(key);
                int changed = before == null ? hashes.size() : diffCount(before, hashes);
                if (before == null || changed > 0) {
                    snaps.computeIfAbsent(key, k -> new ArrayList<>())
                            .add(new Snapshot(e.id, e.createdAtMs, changed, before == null));
                }
                lastSeen.put(key, hashes);
                lastTarget.put(key, g.getValue().targetId);
                lastGroup.put(key, g.getValue());
                lastBackedUp.put(key, e.createdAtMs);
            }

            // Groups seen before, observed this time, and gone: every file deleted.
            for (Map.Entry<String, Map<String, String>> seen : new ArrayList<>(lastSeen.entrySet())) {
                String key = seen.getKey();
                if (now.containsKey(key) || seen.getValue().isEmpty()) continue;
                if (!observedTargets.contains(lastTarget.get(key))) continue;
                snaps.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(new Snapshot(e.id, e.createdAtMs, seen.getValue().size(), false));
                lastSeen.put(key, new HashMap<>());
            }
        }

        // Current device state.
        Map<String, SaveGroup> current = new LinkedHashMap<>();
        for (TargetScan s : scan) {
            if (!s.hasContent() || !registry.hasTarget(s.targetId)) continue;
            Grouping gr = registry.target(s.targetId).grouping;
            for (SaveGroup g : GroupBuilder.build(s.targetId, gr, s.files)) {
                current.put(keyOf(s.targetId, g), g);
            }
        }

        Map<String, Entry> out = new LinkedHashMap<>();
        java.util.Set<String> keys = new java.util.LinkedHashSet<>(current.keySet());
        keys.addAll(lastGroup.keySet());
        for (String key : keys) {
            SaveGroup device = current.get(key);
            SaveGroup stored = lastGroup.get(key);
            SaveGroup shown = device != null ? device : stored;
            List<Snapshot> list = new ArrayList<>(snaps.getOrDefault(key, new ArrayList<>()));
            Collections.reverse(list);
            List<String> copies = new ArrayList<>(safety.getOrDefault(key, new ArrayList<>()));
            Collections.reverse(copies);
            long last = lastBackedUp.getOrDefault(key, 0L);
            Map<String, String> seen = lastSeen.get(key);
            boolean changed = device != null && (seen == null || seen.isEmpty()
                    || !sameFiles(device, stored));
            out.put(key, new Entry(shown.targetId, key, shown, list, copies, last, changed,
                    device != null));
        }
        return out;
    }

    /** The save groups a manifest holds, keyed by {@link #keyOf}. */
    static Map<String, SaveGroup> groupsOf(TargetRegistry registry, Manifest m) {
        Map<String, SaveGroup> out = new LinkedHashMap<>();
        for (ManifestTarget t : m.targets) {
            if (t.files.isEmpty() || !registry.hasTarget(t.id)) continue;
            List<FileStat> stats = new ArrayList<>();
            for (ManifestFile f : t.files) stats.add(new FileStat(f.path, f.size, f.mtimeMs));
            Grouping gr = registry.target(t.id).grouping;
            for (SaveGroup g : GroupBuilder.build(t.id, gr, stats)) out.put(keyOf(t.id, g), g);
        }
        return out;
    }

    private static Map<String, String> hashesOf(Manifest m, SaveGroup g) {
        ManifestTarget t = m.target(g.targetId);
        Map<String, String> byPath = new HashMap<>();
        if (t != null) for (ManifestFile f : t.files) byPath.put(f.path, f.sha256);
        Map<String, String> out = new HashMap<>();
        for (FileStat f : g.files) {
            String h = byPath.get(f.path);
            if (h != null) out.put(f.path, h);
        }
        return out;
    }

    private static int diffCount(Map<String, String> before, Map<String, String> after) {
        int n = 0;
        for (Map.Entry<String, String> e : after.entrySet()) {
            if (!e.getValue().equals(before.get(e.getKey()))) n++;
        }
        for (String p : before.keySet()) if (!after.containsKey(p)) n++;
        return n;
    }

    /** The same fast path the backup uses: size and mtime per path. */
    private static boolean sameFiles(SaveGroup device, SaveGroup stored) {
        if (stored == null || device.files.size() != stored.files.size()) return false;
        Map<String, FileStat> byPath = new HashMap<>();
        for (FileStat f : stored.files) byPath.put(f.path, f);
        for (FileStat f : device.files) {
            FileStat s = byPath.get(f.path);
            if (s == null || s.size != f.size || s.mtimeMs != f.mtimeMs) return false;
        }
        return true;
    }
}
