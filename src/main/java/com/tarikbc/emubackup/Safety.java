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
        ALLOW_NOTIFICATIONS, RECONNECT_DRIVE, SEE_WHAT_HAPPENED
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
        public long lastBackupMs;
        public int gamesTotal;
        /** Games whose saves changed, and stayed unbacked-up, long enough to matter. */
        public int gamesStale;
        public int gamesLocked;
        public boolean scheduled;
        public String scheduleDescription;
        public long nowMs;
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
        if (in.gamesLocked > 0) {
            return new Report(State.ATTENTION, games(in.gamesLocked) + " need extra access.",
                    "Their saves are in folders only the emulator can see. Everything else is "
                            + "backed up.",
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
