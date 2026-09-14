package com.tarikbc.emubackup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates the two files that make a backup recoverable without this app: {@code RESTORE.txt}
 * and {@code SHA256SUMS}.
 *
 * <p>This class is the no-lock-in promise made concrete. If EmuBackup stops working, stops being
 * maintained, or simply is not to hand, the instructions to get every save back are sitting in
 * the backup folder in plain text, and the integrity of every archive can be checked with the
 * stock {@code sha256sum} that ships with every Unix.
 *
 * <p>See {@code FORMAT.md} sections 1 and 7.
 */
public final class RestoreScript {

    /**
     * {@code sha256sum -c} format: lowercase hex, exactly two spaces, then the filename. The
     * two-space separator is not cosmetic; one space means "text mode" to some implementations
     * and the file stops verifying.
     */
    public static String sha256sums(Manifest m) {
        StringBuilder b = new StringBuilder();
        for (ManifestTarget t : m.targets) {
            if (t.archive != null && t.archiveSha256 != null) {
                b.append(t.archiveSha256).append("  ").append(t.archive).append('\n');
            }
        }
        return b.toString();
    }

    /** The per-version instructions, naming real paths and the exact order to extract them. */
    public static String versionReadme(Manifest m) {
        StringBuilder b = new StringBuilder();
        b.append("EmuBackup — ").append(m.version).append('\n');
        b.append("Created ").append(Manifest.iso8601(m.createdAtMs));
        if (m.deviceModel != null) b.append(" on ").append(m.deviceModel);
        b.append("\n\n");

        boolean hasArchives = false;
        for (ManifestTarget t : m.targets) if (t.archive != null) { hasArchives = true; break; }

        b.append("These are ordinary zip files. You do not need EmuBackup to restore them.\n\n");
        if (hasArchives) {
            // Only offered when there is something to check. An instruction that fails when
            // followed is worse than no instruction, and SHA256SUMS is empty in a version where
            // nothing changed.
            b.append("Check them first, from this directory:\n\n");
            b.append("    sha256sum -c SHA256SUMS\n\n");
        } else {
            b.append("Nothing changed since the previous backup, so this version adds no archives\n");
            b.append("of its own. The instructions below point at the versions that do hold your\n");
            b.append("data, and following them from here restores everything as of this date.\n\n");
        }
        b.append("For each save set you want back, run the commands under it in the order\n");
        b.append("shown. Later archives intentionally overwrite earlier ones.\n\n");

        boolean any = false;
        for (ManifestTarget t : m.targets) {
            if (t.chain.isEmpty()) continue;
            any = true;
            b.append("--- ").append(t.id);
            if (t.emulator != null) b.append("  (").append(t.emulator).append(')');
            b.append('\n');
            b.append("    ").append(t.files.size()).append(" files, ")
                    .append(Sizes.human(t.totalBytes())).append('\n');
            if (t.tier == Tier.APP_PRIVATE) {
                b.append("    NOTE: this path is app-private storage. Android will not let a\n");
                b.append("    normal file manager write here; use adb, or restore from the app.\n");
            }
            b.append('\n');
            b.append("    cd '").append(t.root).append("'\n");
            for (String link : t.chain) {
                // ../ because the reader is standing in the target's own directory by now.
                b.append("    unzip -o '<backup folder>/").append(link).append("'\n");
            }
            if (!t.deleted.isEmpty()) {
                b.append("\n    ").append(t.deleted.size())
                        .append(" file(s) were deleted since the base archive was made.\n");
                b.append("    Extracting by hand leaves them in place; remove them if you want an\n");
                b.append("    exact match. They are listed under \"deleted\" in manifest.json.\n");
            }
            b.append('\n');
        }
        if (!any) b.append("No save set in this version has any data yet.\n");

        b.append("Every file's expected size, timestamp and SHA-256 is in manifest.json.\n");
        b.append("That file, not the zip timestamps, is authoritative.\n");
        return b.toString();
    }

    /** The store-level readme, rewritten on every run so it always names the newest version. */
    public static String storeReadme(List<IndexEntry> versions) {
        StringBuilder b = new StringBuilder();
        b.append("EmuBackup\n=========\n\n");
        b.append("Each v#### directory is one backup. They are plain zip files with a\n");
        b.append("manifest.json beside them, readable without this app.\n\n");
        if (versions.isEmpty()) {
            b.append("There are no backups here yet.\n");
            return b.toString();
        }
        b.append("Versions, newest last:\n\n");
        for (IndexEntry e : versions) {
            b.append("    ").append(e.id)
                    .append("  ").append(Manifest.iso8601(e.createdAtMs))
                    .append("  ").append(Sizes.human(e.bytes));
            if (e.pinned) b.append("  [pinned]");
            if (!"manual".equals(e.kind)) b.append("  (").append(e.kind).append(')');
            b.append('\n');
        }
        b.append("\nOpen the newest version's RESTORE.txt for step-by-step instructions.\n");
        b.append("A version directory holds only what changed since the one it builds on, so\n");
        b.append("restoring may need archives from more than one directory. Each RESTORE.txt\n");
        b.append("lists exactly which, in order.\n");
        return b.toString();
    }

    /** Groups a manifest's targets by emulator, for display. */
    public static Map<String, List<ManifestTarget>> byEmulator(Manifest m) {
        Map<String, List<ManifestTarget>> out = new LinkedHashMap<>();
        for (ManifestTarget t : m.targets) {
            String k = t.emulator == null ? "other" : t.emulator;
            List<ManifestTarget> l = out.get(k);
            if (l == null) out.put(k, l = new ArrayList<>());
            l.add(t);
        }
        return out;
    }

    private RestoreScript() {}
}
