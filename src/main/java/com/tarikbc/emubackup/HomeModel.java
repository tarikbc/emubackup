package com.tarikbc.emubackup;

import android.app.NotificationManager;
import android.content.Context;
import java.util.ArrayList;
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
    /** Eden profile usernames by save-folder name, when profiles.dat could be read. */
    final Map<String, String> profileNames;
    /** Save folders the newest backup could not read in full, set-aside ones included. */
    final List<Problem> problems;
    /** Cocoon's art for the games on the device; empty when Cocoon has none. */
    final GameArt.Index art;
    /**
     * True for the first of the two models a load produces: the device has been scanned, the
     * store has not been read yet. {@link #report} is null, {@link #index} empty, and every
     * game's history unknown. The second model, with the store, follows on the same thread.
     */
    final boolean historyPending;

    /** One folder the backup could not read in full. */
    static final class Problem {
        final String targetId, label, emulator, pkg;
        final int files;
        final boolean setAside;

        Problem(String targetId, String label, String emulator, String pkg, int files, boolean setAside) {
            this.targetId = targetId;
            this.label = label;
            this.emulator = emulator;
            this.pkg = pkg;
            this.files = files;
            this.setAside = setAside;
        }
    }

    private HomeModel(Safety.Report report, Safety.Input input, ScanSession scan,
                      String storeError, Map<String, GameHistory.Entry> games, String folderLabel,
                      List<IndexEntry> index, GameNames names, java.util.Set<String> selected,
                      Map<String, String> profileNames, List<Problem> problems, GameArt.Index art,
                      boolean historyPending) {
        this.art = art;
        this.historyPending = historyPending;
        this.report = report;
        this.input = input;
        this.scan = scan;
        this.storeError = storeError;
        this.games = games;
        this.folderLabel = folderLabel;
        this.index = index;
        this.names = names;
        this.selected = selected;
        this.profileNames = profileNames;
        this.problems = problems;
    }

    /**
     * The device alone: scanned, named, no store. Fast, so the screens have something to show
     * while the store (Drive, on a bad day) takes its time. {@link #historyPending} is true.
     */
    static HomeModel local(Context ctx) {
        return build(ctx, ScanSession.run(ctx), null, QUIET);
    }

    /** Where a long read is, in words a screen can show. */
    interface Note {
        void say(String what);
    }

    static final Note QUIET = what -> { };

    /** The same scan with the store's history added. Reads Drive; may take a while. */
    static HomeModel withHistory(Context ctx, HomeModel local, Note note) {
        return build(ctx, local.scan, local, note);
    }

    /** Both stages in one call, for callers that can wait. */
    static HomeModel load(Context ctx) {
        return withHistory(ctx, local(ctx), QUIET);
    }

    /**
     * @param prior the local-stage model when building the full one, so ROM names, art and
     *              profile names are not derived twice; null for the local stage itself
     */
    private static HomeModel build(Context ctx, ScanSession s, HomeModel prior, Note note) {
        boolean withStore = prior != null;
        long now = System.currentTimeMillis();
        android.util.Log.d("EmuBackup", "model: " + (withStore ? "store stage" : "local stage") + " begins");
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
                break;
            }
        }

        String where = Safety.whereName(in.where);
        if (withStore) note.say("Reading the list of backups in " + where + "\u2026");
        RestoreSession.Versions vs = withStore ? RestoreSession.listVersions(ctx)
                : new RestoreSession.Versions(new ArrayList<>(), null);
        if (withStore && in.where != Safety.Where.DEVICE) vs = withLocalSafetyCopies(vs);
        in.storeUnreachable = !vs.reachable();
        if (withStore && !vs.reachable()) {
            note.say(vs.stale ? "Could not reach " + where + "; using the backups it listed last time"
                    : "Could not reach " + where);
        }
        Map<String, GameHistory.Entry> games = new HashMap<>();
        java.util.Set<String> troubled = new java.util.HashSet<>();
        List<Problem> problems = new ArrayList<>();
        // Only with the store: the local stage has no index, and an empty index must never
        // reach the manifest cache, which evicts whatever the index does not list.
        if (withStore && vs.reachable()) {
            for (IndexEntry e : vs.list) {
                if (!e.isPreRestore()) in.lastBackupMs = Math.max(in.lastBackupMs, e.createdAtMs);
            }
            if (s.ok()) {
                Map<String, Manifest> manifests = manifests(ctx, vs.list, where, note);
                note.say("Working out each game's history\u2026");
                games = GameHistory.build(s.registry, vs.list, manifests, s.scans);
                problems = problems(ctx, s.registry, vs.list, manifests);
                for (Problem pr : problems) troubled.add(pr.targetId);
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
        if (withStore && s.ok()) {
            for (GameHistory.Entry g : games.values()) {
                if (!selected.contains(g.targetId) || !GameLabels.looksResetNow(g, vs.list)) continue;
                Target t = s.registry.target(g.targetId);
                in.resetGames.add(GameLabels.displayName(g, t, prior != null ? prior.names : GameNames.empty(),
                        Consoles.badge(s.registry.emulatorOf(g.targetId).id, t.id)));
            }
        }
        if (!BackupService.RUNNING) {
            in.interruptedKind = Prefs.interruptedRun(ctx);
            if (in.interruptedKind != null) in.interruptedAtMs = Prefs.interruptedRunAt(ctx, in.interruptedKind);
        }
        StringBuilder lines = new StringBuilder();
        for (Problem pr : problems) {
            if (pr.setAside) continue;
            in.unreadableFolders++;
            if (lines.length() > 0) lines.append('\n');
            lines.append(pr.label).append(" (").append(pr.emulator).append("): ")
                    .append(pr.files).append(pr.files == 1 ? " file" : " files");
        }
        in.lastRunProblems = lines.length() == 0 ? null : lines.toString();

        GameNames names = prior != null ? prior.names : GameNames.empty();
        GameArt.Index art = prior != null ? prior.art : GameArt.Index.EMPTY;
        if (prior == null && in.storageAccess) {
            try {
                RomIndexer.Index roms = RomIndexer.build(ctx);
                names = roms.names;
                art = roms.art;
            } catch (Exception unreadable) {
                // Raw ids are still correct, just less friendly.
            }
            names = sfoTitles(names, s, games);
        }
        Map<String, String> profiles = prior != null ? prior.profileNames : edenProfiles(s);
        String storeError = withStore ? vs.error : null;
        android.util.Log.d("EmuBackup", "model: " + (withStore ? "store stage" : "local stage") + " done in "
                + (System.currentTimeMillis() - now) + " ms");
        return new HomeModel(withStore ? Safety.assess(in) : null, in, s, storeError, games,
                in.where == Safety.Where.FOLDER ? Destination.folderLabel(ctx) : null,
                vs.list, names, selected, profiles, problems, art, !withStore);
    }

    /** The newest backup in {@link #index}, or 0. For "history as of" when the store is out of reach. */
    long newestIndexedMs() {
        long n = 0;
        for (IndexEntry e : index) if (!e.isPreRestore()) n = Math.max(n, e.createdAtMs);
        return n;
    }

    /**
     * Eden names its profiles in profiles.dat, inside its own folder, so this reads through
     * the same source the scan used: the privileged one when Shizuku is up, else nothing.
     */
    private static Map<String, String> edenProfiles(ScanSession s) {
        Map<String, String> none = java.util.Collections.emptyMap();
        if (s == null || !s.ok()) return none;
        TargetScan ts = s.scanOf("eden-profiles");
        if (ts == null || !ts.hasContent() || ts.resolvedRoot == null) return none;
        String rel = null;
        for (FileStat f : ts.files) if (f.path.endsWith("profiles.dat")) { rel = f.path; break; }
        if (rel == null) return none;
        try {
            Target t = s.registry.target("eden-profiles");
            FileSource src = t.tier == Tier.SHARED ? new LocalFileSource()
                    : (s.shizuku.ready() ? new RemoteFileSource(ShizukuGate.service()) : null);
            if (src == null) return none;
            try (java.io.InputStream in = src.open(ts.resolvedRoot, rel)) {
                return EdenProfiles.parse(in.readAllBytes());
            }
        } catch (Exception unreadable) {
            return none;
        }
    }

    /**
     * A PSP save carries its game's title in PARAM.SFO. Read for each PSP group on the device,
     * so those games are named even when the disc is not in the library.
     */
    private static GameNames sfoTitles(GameNames names, ScanSession s, Map<String, GameHistory.Entry> games) {
        if (s == null || !s.ok()) return names;
        for (GameHistory.Entry e : games.values()) {
            if (!e.onDevice || e.group.gameIdKind != IdKind.PSP_GAME_ID || e.group.gameKey == null) continue;
            if (names.isKnown(IdKind.PSP_GAME_ID, e.group.gameKey)) continue;
            TargetScan ts = s.scanOf(e.targetId);
            if (ts == null || ts.resolvedRoot == null) continue;
            for (FileStat f : e.group.files) {
                if (!f.path.endsWith("/PARAM.SFO")) continue;
                try {
                    byte[] b = java.nio.file.Files.readAllBytes(new java.io.File(ts.resolvedRoot, f.path).toPath());
                    String title = Sfo.parse(b).get("TITLE");
                    if (title != null && !title.isEmpty()) names = names.with(IdKind.PSP_GAME_ID, e.group.gameKey, title);
                } catch (Exception ignored) {
                    // Unreadable metadata leaves the id as it was.
                }
                break;
            }
        }
        return names;
    }

    /**
     * Safety copies written to the device's own folder, added to the store's versions. They are
     * what a restore made when the store was Drive; the Games page lists them as safety copies
     * and a restore of one reads the device folder.
     */
    private static RestoreSession.Versions withLocalSafetyCopies(RestoreSession.Versions vs) {
        try {
            BackupSink local = Stores.safetyCopySink();
            List<IndexEntry> merged = new ArrayList<>(vs.list);
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (IndexEntry e : merged) ids.add(e.id);
            for (IndexEntry e : BackupIndex.fromJson(new String(local.readRootFile(BackupIndex.FILE_NAME),
                    java.nio.charset.StandardCharsets.UTF_8)).versions()) {
                if (e.isPreRestore() && !ids.contains(e.id) && Stores.isLocalSafetyCopy(e.id)) merged.add(e);
            }
            return new RestoreSession.Versions(merged, vs.error, vs.stale);
        } catch (Exception none) {
            // No local folder, or no index in it: nothing to add.
            return vs;
        }
    }

    private static Map<String, Manifest> manifests(Context ctx, List<IndexEntry> index, String where,
                                                   Note note) {
        ManifestCache cache = new ManifestCache(ctx);
        cache.evictNotIn(index);
        Map<String, Manifest> manifests = new HashMap<>();
        BackupSink store = null;
        int toFetch = 0;
        for (IndexEntry e : index) if (!cache.has(e.id) && !Stores.isLocalSafetyCopy(e.id)) toFetch++;
        int fetched = 0, failed = 0;
        for (IndexEntry e : index) {
            long t0 = System.currentTimeMillis();
            boolean cached = cache.has(e.id);
            try {
                if (Stores.isLocalSafetyCopy(e.id)) {
                    manifests.put(e.id, cache.get(Stores.safetyCopySink(), e.id));
                    continue;
                }
                if (!cached) {
                    fetched++;
                    note.say("Reading backup " + fetched + " of " + toFetch + " from " + where + " ("
                            + When.format(e.createdAtMs, System.currentTimeMillis()) + ")\u2026"
                            + (failed > 0 ? " " + failed + " could not be read." : ""));
                    if (store == null) store = Stores.active(ctx);
                }
                manifests.put(e.id, cache.get(store, e.id));
                if (!cached) android.util.Log.d("EmuBackup", "store: manifest " + e.id + " fetched in "
                        + (System.currentTimeMillis() - t0) + " ms");
            } catch (Exception unreadable) {
                failed++;
                android.util.Log.d("EmuBackup", "store: manifest " + e.id + " unreadable after "
                        + (System.currentTimeMillis() - t0) + " ms: " + unreadable.getMessage());
                // A manifest that cannot be read is a backup that is not observed. GameHistory
                // treats absence as "not seen", never as "deleted".
            }
        }
        return manifests;
    }

    /** Save folders the newest backup could not read in full, by that backup's own account. */
    private static List<Problem> problems(Context ctx, TargetRegistry reg, List<IndexEntry> index,
                                          Map<String, Manifest> manifests) {
        List<Problem> out = new ArrayList<>();
        IndexEntry newest = null;
        for (IndexEntry e : index) {
            if (e.isPreRestore() || !manifests.containsKey(e.id)) continue;
            if (newest == null || e.createdAtMs > newest.createdAtMs) newest = e;
        }
        if (newest == null) return out;
        java.util.Set<String> aside = Prefs.setAside(ctx);
        for (ManifestTarget t : manifests.get(newest.id).targets) {
            boolean ok = t.status == TargetStatus.OK || t.status == TargetStatus.EMPTY;
            if (ok && t.detail == null) continue;
            if (!reg.hasTarget(t.id)) continue;
            Target target = reg.target(t.id);
            Emulator em = reg.emulatorOf(t.id);
            int files = 0;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+) file\\(?s?\\)? could not be read")
                    .matcher(t.detail == null ? "" : t.detail);
            if (m.find()) files = Integer.parseInt(m.group(1));
            // Only files the backup could not read count here. A folder with another status
            // (locked, over its cap) has its own place in Safety.
            if (files == 0) continue;
            String emu = em.label;
            int cut = emu.indexOf(" (");
            if (cut > 0) emu = emu.substring(0, cut);
            out.add(new Problem(t.id, target.label, emu, target.pkg, files, aside.contains(t.id)));
        }
        return out;
    }

    String whereName() {
        if (input.where == Safety.Where.FOLDER && folderLabel != null) return folderLabel;
        return Safety.whereName(input.where);
    }
}
