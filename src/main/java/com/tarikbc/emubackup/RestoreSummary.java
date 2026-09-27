package com.tarikbc.emubackup;

import java.util.ArrayList;
import java.util.List;

/**
 * The words a person reads before one game is put back: what is written, what is replaced,
 * what is left alone, and which button does what.
 *
 * <p>Two plans can come out of one preview. The unforced plan never touches a file that is
 * newer on the device; the forced one replaces those too. When both would write something the
 * sheet offers both, with the safe one as the secondary button, so the choice is the person's
 * and not a default they never saw. Android-free; see {@code test.sh}.
 */
public final class RestoreSummary {

    /** True when the device already matches the backup: nothing to write either way. */
    public final boolean alreadyThere;
    public final String title;
    public final String body;
    public final String primary;
    /** Null when the sheet has no secondary button. */
    public final String secondary;
    /** The plan the primary button runs. Null when {@link #alreadyThere}. */
    public final RestorePlan primaryPlan;
    /** The plan the secondary button runs, or null when the secondary button only closes. */
    public final RestorePlan secondaryPlan;

    private RestoreSummary(boolean alreadyThere, String title, String body, String primary,
                           String secondary, RestorePlan primaryPlan, RestorePlan secondaryPlan) {
        this.alreadyThere = alreadyThere;
        this.title = title;
        this.body = body;
        this.primary = primary;
        this.secondary = secondary;
        this.primaryPlan = primaryPlan;
        this.secondaryPlan = secondaryPlan;
    }

    /**
     * @param plan     the preview for this game's files, forced or not; both readings are derived
     * @param profile  the profile the save belongs to, or null
     * @param when     the backup's moment, already in words ("Wed 16 Sep, 12:00 PM")
     * @param emulator the emulator's short name, for the closing line
     */
    public static RestoreSummary describe(RestorePlan plan, String game, String profile, String when,
                                          String emulator, long nowMs) {
        RestorePlan unforced = plan.withForced(false);
        RestorePlan forced = plan.withForced(true);
        String title = "Put back " + game
                + (profile == null || profile.isEmpty() ? "" : " (" + profile + ")")
                + " from " + when + "?";
        if (forced.toWrite().isEmpty()) {
            return new RestoreSummary(true, "Already there",
                    "Your current save already matches the backup from " + when + ".",
                    "OK", null, null, null);
        }

        List<RestoreItem> creates = of(plan, RestoreAction.CREATE);
        List<RestoreItem> older = of(plan, RestoreAction.OVERWRITE_OLDER);
        int same = plan.count(RestoreAction.SKIP_IDENTICAL);
        int suspect = plan.count(RestoreAction.SIZE_CONFLICT);
        int newer = plan.count(RestoreAction.CONFLICT_NEWER);
        int orphans = plan.count(RestoreAction.ORPHAN_ON_DEVICE);

        List<String> lines = new ArrayList<>();
        if (!creates.isEmpty()) {
            lines.add("Puts back " + count(creates.size(), "missing file") + " (" + Sizes.human(bytes(creates))
                    + ")" + names(creates) + ".");
        }
        if (!older.isEmpty()) {
            lines.add("Replaces " + count(older.size(), "older file") + " (" + Sizes.human(bytes(older)) + ")"
                    + names(older) + "; a safety copy is made first, so this can be undone.");
        }
        if (suspect > 0) {
            lines.add(count(suspect, "file") + (suspect == 1 ? " has" : " have")
                    + " the same date but different content; " + (suspect == 1 ? "it is" : "they are")
                    + " replaced too" + (older.isEmpty() ? ", after a safety copy" : "") + ".");
        }
        if (newer > 0) {
            lines.add(count(newer, "file") + " on this device " + (newer == 1 ? "is" : "are") + " newer (from "
                    + When.format(plan.newestConflictMtime(), nowMs) + "). Keeping "
                    + (newer == 1 ? "it" : "them") + " replaces nothing; replacing "
                    + (newer == 1 ? "it" : "them") + " makes a safety copy first, so it can be undone.");
        } else if (older.isEmpty() && suspect == 0) {
            lines.add("Nothing on the device is replaced.");
        }
        if (same > 0) {
            lines.add(count(same, "file") + " already " + (same == 1 ? "matches and stays as it is." : "match and stay as they are."));
        }
        if (orphans > 0) {
            lines.add(count(orphans, "file") + " added since then " + (orphans == 1 ? "is" : "are") + " left in place.");
        }
        lines.add(emulator + " may need relaunching to notice.");
        String body = String.join("\n\n", lines);

        boolean replaceNeeded = newer > 0;
        if (replaceNeeded && !unforced.toWrite().isEmpty()) {
            return new RestoreSummary(false, title, body, "Replace them anyway", "Keep newer files", forced, unforced);
        }
        if (replaceNeeded) {
            return new RestoreSummary(false, title, body, newer == 1 ? "Replace it anyway" : "Replace them anyway",
                    "Cancel", forced, null);
        }
        // Same-date-different-content files are replaced along with the rest; the body said so.
        return new RestoreSummary(false, title, body, "Put it back", "Cancel", suspect > 0 ? forced : unforced, null);
    }

    private static List<RestoreItem> of(RestorePlan plan, RestoreAction a) {
        List<RestoreItem> out = new ArrayList<>();
        for (RestoreItem i : plan.items) if (i.action == a) out.add(i);
        return out;
    }

    private static long bytes(List<RestoreItem> items) {
        long n = 0;
        for (RestoreItem i : items) n += i.size();
        return n;
    }

    /** ": a.dat, b.dat" for up to three files; nothing beyond that, the count already said it. */
    private static String names(List<RestoreItem> items) {
        if (items.size() > 3) return "";
        List<String> out = new ArrayList<>();
        for (RestoreItem i : items) {
            int cut = i.path.lastIndexOf('/');
            out.add(cut < 0 ? i.path : i.path.substring(cut + 1));
        }
        return ": " + String.join(", ", out);
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }
}
