package com.tarikbc.emubackup;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Works out what restoring a target would do, before anything is written.
 *
 * <p>The preview this produces is never skippable. Restore is the one operation here that can
 * destroy data, and the failure it must prevent is specific: restoring an older backup over
 * progress made since. So a file that is newer on the device is classified separately from one
 * that is merely different, and is left alone unless the user overrides it for that target with
 * the count and the newest timestamp in front of them.
 *
 * <p>Android-free by design; see {@code test.sh}.
 */
public final class RestorePlanner {

    /** Hashes a device file, so identical content can be told from merely equal size. */
    public interface Hasher {
        String sha256(String relPath) throws IOException;
    }

    /**
     * @param selected paths to consider, or null for every file in the backup
     * @param readable whether this tier can be written at all right now
     */
    public static RestorePlan plan(ManifestTarget backup, String label, List<FileStat> onDevice,
                                   Set<String> selected, Hasher hasher, boolean readable)
            throws IOException {

        Map<String, FileStat> device = new LinkedHashMap<>();
        for (FileStat f : onDevice) device.put(f.path, f);

        List<RestoreItem> items = new ArrayList<>();

        for (ManifestFile b : backup.files) {
            if (selected != null && !selected.contains(b.path)) continue;
            FileStat d = device.remove(b.path);

            if (!readable) {
                items.add(new RestoreItem(b.path, RestoreAction.BLOCKED_TIER, b, d));
                continue;
            }
            if (d == null) {
                items.add(new RestoreItem(b.path, RestoreAction.CREATE, b, null));
                continue;
            }

            // Only hash when the sizes match. A different size already proves the content differs,
            // and reading a file to learn something already known is waste on a save tree this big.
            boolean identical = false;
            if (d.size == b.size) {
                identical = hasher.sha256(b.path).equals(b.sha256);
            }
            if (identical) {
                items.add(new RestoreItem(b.path, RestoreAction.SKIP_IDENTICAL, b, d));
            } else if (d.mtimeMs > b.mtimeMs) {
                items.add(new RestoreItem(b.path, RestoreAction.CONFLICT_NEWER, b, d));
            } else if (d.mtimeMs == b.mtimeMs) {
                // Equal timestamps but different bytes means something wrote without touching the
                // mtime. Treated as suspect rather than as a routine overwrite.
                items.add(new RestoreItem(b.path, RestoreAction.SIZE_CONFLICT, b, d));
            } else {
                items.add(new RestoreItem(b.path, RestoreAction.OVERWRITE_OLDER, b, d));
            }
        }

        // Anything left on the device is not in the backup. Listed, never deleted: a restore that
        // silently removes files the user made since is indistinguishable from data loss.
        for (FileStat d : device.values()) {
            if (selected != null && !selected.contains(d.path)) continue;
            items.add(new RestoreItem(d.path, RestoreAction.ORPHAN_ON_DEVICE, null, d));
        }

        return new RestorePlan(backup.id, label, backup.root, backup.tier, items, false);
    }

    private RestorePlanner() {}
}
