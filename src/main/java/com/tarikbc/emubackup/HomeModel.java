package com.tarikbc.emubackup;

import android.app.NotificationManager;
import android.content.Context;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything Home shows, gathered off the main thread in one go.
 *
 * <p>Rescanned on every resume rather than cached, because both capabilities this app depends
 * on can disappear between visits: storage access can be revoked in Settings, and Shizuku does
 * not survive a reboot. A stale "you are safe" is the failure mode the app exists to prevent.
 */
final class HomeModel {

    final Safety.Report report;
    final Safety.Input input;
    final ScanSession scan;
    /** Null when the store was read. */
    final String storeError;
    /** Group key to history, empty when the store or the scan is unavailable. */
    final Map<String, GameHistory.Entry> games;
    /** The picked folder's name, when that is where backups go. */
    final String folderLabel;
    /** Every version in the store, oldest first; empty when unreachable. */
    final List<IndexEntry> index;
    /** Names for game ids, derived from the ROM library. Empty when storage is unreadable. */
    final GameNames names;
    /** Target ids the backup covers, per the current settings. */
    final java.util.Set<String> selected;

    private HomeModel(Safety.Report report, Safety.Input input, ScanSession scan,
                      String storeError, Map<String, GameHistory.Entry> games, String folderLabel,
                      List<IndexEntry> index, GameNames names, java.util.Set<String> selected) {
        this.report = report;
        this.input = input;
        this.scan = scan;
        this.storeError = storeError;
        this.games = games;
        this.folderLabel = folderLabel;
        this.index = index;
        this.names = names;
        this.selected = selected;
    }

    static HomeModel load(Context ctx) {
        long now = System.currentTimeMillis();
        ScanSession s = ScanSession.run(ctx);
        Settings set = Prefs.settings(ctx);
        RunLog log = Prefs.log(ctx);

        Safety.Input in = new Safety.Input();
        in.nowMs = now;
        in.onboarded = Onboarding.isComplete(ctx);
        in.storageAccess = Permissions.hasAllFiles();
        in.notificationsAllowed = ctx.getSystemService(NotificationManager.class)
                .areNotificationsEnabled();
        switch (Destination.effective(ctx)) {
            case DRIVE: in.where = Safety.Where.DRIVE; break;
            case FOLDER: in.where = Safety.Where.FOLDER; break;
            default: in.where = Safety.Where.DEVICE; break;
        }
        in.destinationUnavailable = Destination.chosenButUnavailable(ctx);
        in.scheduled = set.scheduled();
        in.scheduleDescription = set.describeSchedule();
        if (set.scheduled() && !BackupJobScheduler.isScheduled(ctx)) {
            // A force-stop (from Settings, or adb) drops every JobScheduler job the app owns,
            // and the schedule would then silently be a setting with nothing behind it. Put it
            // back rather than ask the person to toggle it; Settings still says so if this fails.
            BackupJobScheduler.apply(ctx, set);
        }
        in.consecutiveScheduledFailures = log.consecutiveFailures();
        for (RunLog.Run r : log.runs()) {
            // The newest backup run of either kind. A restore is a run too, but not this one.
            if ("restore".equals(r.kind)) continue;
            in.lastRunFailed = !r.ok;
            in.lastFailureReason = r.ok ? null : r.detail;
            break;
        }
        for (RunLog.Run r : log.runs()) {
            if (r.ok && !"restore".equals(r.kind)) {
                in.lastBackupMs = r.atMs;
                in.lastRunProblems = r.detail == null || r.detail.isEmpty() ? null : r.detail;
                break;
            }
        }

        RestoreSession.Versions vs = RestoreSession.listVersions(ctx);
        in.storeUnreachable = !vs.reachable();
        Map<String, GameHistory.Entry> games = new HashMap<>();
        java.util.Set<String> troubled = new java.util.HashSet<>();
        if (vs.reachable()) {
            for (IndexEntry e : vs.list) {
                if (!e.isPreRestore()) in.lastBackupMs = Math.max(in.lastBackupMs, e.createdAtMs);
            }
            if (s.ok()) {
                Map<String, Manifest> manifests = manifests(ctx, vs.list);
                games = GameHistory.build(s.registry, vs.list, manifests, s.scans);
                troubled = troubledTargets(vs.list, manifests);
            }
        } else if (s.ok()) {
            games = GameHistory.build(s.registry, java.util.Collections.emptyList(),
                    java.util.Collections.emptyMap(), s.scans);
        }

        // Only what the backup actually covers. Save states left out by choice are not
        // "games changed since the last backup"; they are games the person chose not to keep.
        java.util.Set<String> selected = new java.util.HashSet<>();
        if (s.ok()) {
            java.util.Collection<String> chosen = set.selectedTargets(s.registry);
            if (chosen != null) selected.addAll(chosen);
            else for (Target t : s.registry.defaultEnabledTargets()) selected.add(t.id);
        }
        for (GameHistory.Entry g : games.values()) {
            if (!g.onDevice || !selected.contains(g.targetId)) continue;
            in.gamesTotal++;
            // A target the last backup could not fully read is reported as that, through
            // lastRunProblems, rather than as games that changed.
            if (troubled.contains(g.targetId)) continue;
            // A group can only have changed after a backup if something in it is newer than
            // that backup. Older files the backup does not hold are files it could not read,
            // and those are reported through lastRunProblems instead.
            boolean afterBackup = g.newestMtimeMs() > g.lastBackedUpMs;
            if (g.changedSinceBackup && afterBackup
                    && now - g.newestMtimeMs() > Safety.STALE_AFTER_MS) {
                in.gamesStale++;
            }
        }
        int locked = 0;
        if (s.ok()) {
            for (TargetScan ts : s.scans) {
                if (ts.status == TargetStatus.TIER_UNAVAILABLE && selected.contains(ts.targetId)) locked++;
            }
        }
        in.gamesLocked = locked;

        GameNames names = GameNames.empty();
        if (in.storageAccess) {
            try {
                names = RomIndexer.build(ctx).names;
            } catch (Exception unreadable) {
                // Raw ids are still correct, just less friendly.
            }
        }
        return new HomeModel(Safety.assess(in), in, s, vs.error, games,
                in.where == Safety.Where.FOLDER ? Destination.folderLabel(ctx) : null,
                vs.list, names, selected);
    }

    private static Map<String, Manifest> manifests(Context ctx, List<IndexEntry> index) {
        ManifestCache cache = new ManifestCache(ctx);
        cache.evictNotIn(index);
        Map<String, Manifest> manifests = new HashMap<>();
        BackupSink store = null;
        for (IndexEntry e : index) {
            try {
                if (!cache.has(e.id) && store == null) store = Stores.active(ctx);
                manifests.put(e.id, cache.get(store, e.id));
            } catch (Exception unreadable) {
                // A manifest that cannot be read is a backup that is not observed. GameHistory
                // treats absence as "not seen", never as "deleted".
            }
        }
        return manifests;
    }

    /** Targets the newest backup could not read in full, by that backup's own account. */
    private static java.util.Set<String> troubledTargets(List<IndexEntry> index,
                                                         Map<String, Manifest> manifests) {
        java.util.Set<String> out = new java.util.HashSet<>();
        IndexEntry newest = null;
        for (IndexEntry e : index) {
            if (e.isPreRestore() || !manifests.containsKey(e.id)) continue;
            if (newest == null || e.createdAtMs > newest.createdAtMs) newest = e;
        }
        if (newest == null) return out;
        for (ManifestTarget t : manifests.get(newest.id).targets) {
            boolean ok = t.status == TargetStatus.OK || t.status == TargetStatus.EMPTY;
            if (!ok || t.detail != null) out.add(t.id);
        }
        return out;
    }

    String whereName() {
        if (input.where == Safety.Where.FOLDER && folderLabel != null) return folderLabel;
        return Safety.whereName(input.where);
    }
}
