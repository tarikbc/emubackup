package com.tarikbc.emubackup;

/**
 * The one answer the home screen gives: are my saves safe, and if not, what is the one thing
 * to do about it.
 *
 * <p>Every input the app knows about is folded into four states and a single action, in a
 * fixed priority. Never two headlines, never two buttons. If several things are wrong, the
 * worst one wins and the rest wait their turn; a person can only do one thing at a time and a
 * screen that lists five is a screen that gets ignored.
 *
 * <p>Table-driven and pure so every combination is tested on the JVM. See {@code test.sh}.
 */
public final class Safety {

    public enum State { NOT_SET_UP, PROBLEM, ATTENTION, SAFE }

    public enum Action {
        START, BACK_UP_NOW, GRANT_STORAGE, SET_UP_EXTRA_ACCESS, CHOOSE_DESTINATION,
        ALLOW_NOTIFICATIONS, RECONNECT_DRIVE, SEE_WHAT_HAPPENED, SEE_WHAT_TO_DO, SEE_GAMES
    }

    public enum Where { DRIVE, FOLDER, DEVICE }

    /** Everything the decision needs, gathered by the Android layer. */
    public static final class Input {
        public boolean onboarded;
        public boolean storageAccess;
        public boolean notificationsAllowed;
        public Where where = Where.DEVICE;
        /** A stronger destination was chosen but is not usable, so backups fell back. */
        public boolean destinationUnavailable;
        /** The store could not be read at all. */
        public boolean storeUnreachable;
        public int consecutiveScheduledFailures;
        public boolean lastRunFailed;
        public String lastFailureReason;
        /** Save folders the last backup could not read in full, not counting those set aside. */
        public int unreadableFolders;
        /** Those folders named, one line each, for the detail under the headline. */
        public String lastRunProblems;
        public long lastBackupMs;
        public int gamesTotal;
        /** Games whose saves changed, and stayed unbacked-up, long enough to matter. */
        public int gamesStale;
        /** Save folders the app cannot see into. Their games are unknown, so folders are counted. */
        public int gamesLocked;
        public boolean scheduled;
        public String scheduleDescription;
        public long nowMs;
        /** Games whose save looked reset at the newest backup, by name. */
        public java.util.List<String> resetGames = new java.util.ArrayList<>();
        /** "backup" or "restore" when the app was stopped in the middle of one; else null. */
        public String interruptedKind;
        public long interruptedAtMs;
        /** When the app last found its scheduled job gone and put it back; 0 when it never did. */
        public long scheduleDroppedAtMs;
        /** True when a one-off catch-up job was registered because a run was already overdue. */
        public boolean catchUpRegistered;
    }

    public static final class Report {
        public final State state;
        public final String headline;
        public final String detail;
        public final Action action;
        public final String actionLabel;

        Report(State state, String headline, String detail, Action action, String actionLabel) {
            this.state = state;
            this.headline = headline;
            this.detail = detail;
            this.action = action;
            this.actionLabel = actionLabel;
        }
    }

    private Safety() {}

    /** Changes older than this, still not backed up, count as stale for the amber rule. */
    public static final long STALE_AFTER_MS = 24L * 60 * 60 * 1000;

    public static Report assess(Input in) {
        // Problem: something is broken and backups are not happening as promised.
        if (!in.storageAccess) {
            return new Report(State.PROBLEM, "EmuBackup can't read your saves.",
                    "Storage access was turned off, so nothing can be backed up until it is on.",
                    Action.GRANT_STORAGE, "Grant storage access");
        }
        if (in.consecutiveScheduledFailures >= 2) {
            return new Report(State.PROBLEM, "Backups have been failing.",
                    in.consecutiveScheduledFailures + " in a row. Last reason: "
                            + reason(in.lastFailureReason),
                    in.where == Where.DRIVE ? Action.RECONNECT_DRIVE : Action.SEE_WHAT_HAPPENED,
                    in.where == Where.DRIVE ? "Reconnect Google Drive" : "See what happened");
        }
        if (in.storeUnreachable) {
            return new Report(State.PROBLEM, whereName(in.where) + " can't be reached.",
                    "Your backups are there, but nothing can be read or written right now.",
                    in.where == Where.DRIVE ? Action.RECONNECT_DRIVE : Action.CHOOSE_DESTINATION,
                    in.where == Where.DRIVE ? "Reconnect Google Drive" : "Check where backups go");
        }
        if (in.destinationUnavailable) {
            return new Report(State.PROBLEM, "Backups are going to this device instead.",
                    "The place you chose is not available, so nothing is leaving the device.",
                    Action.CHOOSE_DESTINATION, "Choose where backups go");
        }

        if (!in.onboarded) {
            return new Report(State.NOT_SET_UP, "Let's find your saves.",
                    "One permission, then you see what is on this device.",
                    Action.START, "Start");
        }

        // Attention: nothing is broken, but you would want to know.
        if (in.lastBackupMs <= 0) {
            return new Report(State.ATTENTION, "No backup yet.",
                    in.gamesTotal > 0 ? games(in.gamesTotal) + " found, none backed up."
                                      : "Nothing has been backed up so far.",
                    Action.BACK_UP_NOW, "Back up now");
        }
        if (in.interruptedKind != null) {
            boolean restore = "restore".equals(in.interruptedKind);
            return new Report(State.ATTENTION,
                    (restore ? "A restore" : "A backup") + " was cut short.",
                    "It started " + Ago.format(in.interruptedAtMs, in.nowMs) + " and the app was stopped "
                            + "before it finished. Nothing half-written was left behind. "
                            + (restore ? "Check the game, or put it back again."
                                       : "Run it again to be sure."),
                    restore ? Action.SEE_GAMES : Action.BACK_UP_NOW,
                    restore ? "Open Games" : "Back up now");
        }
        if (in.resetGames != null && !in.resetGames.isEmpty()) {
            int n = in.resetGames.size();
            return new Report(State.ATTENTION,
                    n == 1 ? in.resetGames.get(0) + " looks like it started over."
                           : n + " games look like they started over.",
                    (n == 1 ? "Its save got smaller at the last backup with nothing else changed. "
                            : "Their saves got smaller at the last backup with nothing else changed. ")
                            + "The copy from before is kept and will not be pruned. If the game "
                            + "really did reset, put that copy back from its page.",
                    Action.SEE_GAMES, "Open Games");
        }
        if (in.scheduled && in.scheduleDroppedAtMs > 0) {
            // Android drops every job an app owns when the app is force-stopped, and on some
            // handhelds the recents screen does exactly that. The schedule was put back when
            // the app opened, but the person has to hear why it did not run, or it will keep
            // not running and Home will keep saying "Safe".
            return new Report(State.ATTENTION, "Your automatic backup was switched off.",
                    "Android removes the schedule when EmuBackup is closed from the recents "
                            + "screen or force-stopped. It was put back when you opened the app"
                            + (in.catchUpRegistered
                                    ? ", and a catch-up runs as soon as the conditions hold ("
                                            + in.scheduleDescription + ")."
                                    : " (" + in.scheduleDescription + ").")
                            + " To keep the schedule, leave EmuBackup in the background instead "
                            + "of swiping it away.",
                    Action.BACK_UP_NOW, "Back up now");
        }
        if (in.gamesStale > 0) {
            return new Report(State.ATTENTION,
                    games(in.gamesStale) + " changed since the last backup.",
                    "Last backup " + Ago.format(in.lastBackupMs, in.nowMs) + ".",
                    Action.BACK_UP_NOW, "Back up now");
        }
        if (in.lastRunFailed) {
            return new Report(State.ATTENTION, "The last backup failed.",
                    reason(in.lastFailureReason),
                    Action.SEE_WHAT_HAPPENED, "See what happened");
        }
        if (in.unreadableFolders > 0) {
            // Files the backup could not read are files that are not backed up, however green
            // everything else looks. An emulator wrote them with no group access, which
            // Shizuku's shell user cannot get past. What to do differs per app.
            return new Report(State.ATTENTION, in.unreadableFolders == 1
                    ? "1 save folder has files only its app can read."
                    : in.unreadableFolders + " save folders have files only their apps can read.",
                    (in.lastRunProblems == null ? "" : in.lastRunProblems + "\n")
                            + "Everything else is backed up.",
                    Action.SEE_WHAT_TO_DO, "See what to do");
        }
        if (in.gamesLocked > 0) {
            return new Report(State.ATTENTION, in.gamesLocked == 1
                    ? "1 save folder needs extra access."
                    : in.gamesLocked + " save folders need extra access.",
                    "Only the emulator can see inside. Everything else is backed up.",
                    Action.SET_UP_EXTRA_ACCESS, "Set up extra access");
        }
        if (in.where == Where.DEVICE) {
            return new Report(State.ATTENTION, "Backups stay on this device only.",
                    "That protects against an emulator wiping its saves, and nothing else.",
                    Action.CHOOSE_DESTINATION, "Choose where backups go");
        }
        if (!in.notificationsAllowed) {
            return new Report(State.ATTENTION, "EmuBackup can't tell you if a backup fails.",
                    "Notifications are off. A schedule that fails quietly is not a backup.",
                    Action.ALLOW_NOTIFICATIONS, "Allow notifications");
        }

        StringBuilder d = new StringBuilder("Last backup ")
                .append(Ago.format(in.lastBackupMs, in.nowMs))
                .append(" · ").append(whereName(in.where));
        if (in.scheduled && in.scheduleDescription != null) {
            d.append("\nNext: ").append(in.scheduleDescription);
        }
        if (in.gamesTotal > 0) d.append("\n").append(in.gamesTotal).append(" of ")
                .append(in.gamesTotal).append(" games");
        return new Report(State.SAFE, "Your saves are backed up.", d.toString(),
                Action.BACK_UP_NOW, "Back up now");
    }

    private static String games(int n) {
        return n + (n == 1 ? " game" : " games");
    }

    private static String reason(String r) {
        return r == null || r.isEmpty() ? "unknown" : r;
    }

    public static String whereName(Where w) {
        switch (w) {
            case DRIVE: return "Google Drive";
            case FOLDER: return "your folder";
            default: return "this device";
        }
    }
}
