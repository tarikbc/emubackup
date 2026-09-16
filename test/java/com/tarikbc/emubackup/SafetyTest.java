package com.tarikbc.emubackup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SafetyTest {

    private static final long NOW = 1_800_000_000_000L;

    /** A healthy install: everything granted, backed up an hour ago to Drive, scheduled. */
    private static Safety.Input healthy() {
        Safety.Input in = new Safety.Input();
        in.onboarded = true;
        in.storageAccess = true;
        in.notificationsAllowed = true;
        in.where = Safety.Where.DRIVE;
        in.lastBackupMs = NOW - 3_600_000L;
        in.gamesTotal = 17;
        in.scheduled = true;
        in.scheduleDescription = "tonight, on charge";
        in.nowMs = NOW;
        return in;
    }

    @Test void healthyIsSafe() {
        Safety.Report r = Safety.assess(healthy());
        assertEquals(Safety.State.SAFE, r.state);
        assertEquals(Safety.Action.BACK_UP_NOW, r.action);
        assertTrue(r.detail.contains("1 hour ago"), r.detail);
        assertTrue(r.detail.contains("Google Drive"), r.detail);
        assertTrue(r.detail.contains("17 of 17"), r.detail);
    }

    @Test
    @DisplayName("no storage access is a problem even on a fresh install")
    void storageFirst() {
        Safety.Input in = healthy();
        in.storageAccess = false;
        in.onboarded = false;
        Safety.Report r = Safety.assess(in);
        assertEquals(Safety.State.PROBLEM, r.state);
        assertEquals(Safety.Action.GRANT_STORAGE, r.action);
    }

    @Test
    @DisplayName("a fresh install with access is 'not set up', before any attention item")
    void notSetUpBeatsAttention() {
        Safety.Input in = healthy();
        in.onboarded = false;
        in.lastBackupMs = 0;
        in.where = Safety.Where.DEVICE;
        Safety.Report r = Safety.assess(in);
        assertEquals(Safety.State.NOT_SET_UP, r.state);
        assertEquals(Safety.Action.START, r.action);
    }

    @Test
    @DisplayName("repeated scheduled failures outrank everything but storage")
    void failuresAreAProblem() {
        Safety.Input in = healthy();
        in.consecutiveScheduledFailures = 2;
        in.lastFailureReason = "invalid_grant";
        Safety.Report r = Safety.assess(in);
        assertEquals(Safety.State.PROBLEM, r.state);
        assertEquals(Safety.Action.RECONNECT_DRIVE, r.action);
        assertTrue(r.detail.contains("invalid_grant"), r.detail);
    }

    @Test
    @DisplayName("one failed run is attention, not a crisis")
    void oneFailureIsAttention() {
        Safety.Input in = healthy();
        in.lastRunFailed = true;
        in.lastFailureReason = "no network";
        Safety.Report r = Safety.assess(in);
        assertEquals(Safety.State.ATTENTION, r.state);
        assertEquals(Safety.Action.SEE_WHAT_HAPPENED, r.action);
    }

    @Test void unreachableStore() {
        Safety.Input in = healthy();
        in.storeUnreachable = true;
        Safety.Report r = Safety.assess(in);
        assertEquals(Safety.State.PROBLEM, r.state);
        assertTrue(r.headline.contains("Google Drive"), r.headline);
        assertEquals(Safety.Action.RECONNECT_DRIVE, r.action);
    }

    @Test void fallbackDestination() {
        Safety.Input in = healthy();
        in.destinationUnavailable = true;
        Safety.Report r = Safety.assess(in);
        assertEquals(Safety.State.PROBLEM, r.state);
        assertEquals(Safety.Action.CHOOSE_DESTINATION, r.action);
    }

    @Test
    @DisplayName("attention items come in a fixed order")
    void attentionOrder() {
        Safety.Input in = healthy();
        in.lastBackupMs = 0;
        in.gamesStale = 3;
        in.gamesLocked = 2;
        in.where = Safety.Where.DEVICE;
        in.notificationsAllowed = false;
        assertEquals("No backup yet.", Safety.assess(in).headline);

        in.lastBackupMs = NOW - 7_200_000L;
        assertEquals("3 games changed since the last backup.", Safety.assess(in).headline);

        in.gamesStale = 0;
        in.unreadableFolders = 2;
        in.lastRunProblems = "Memory cards (DuckStation): 1 file\nWorlds (Amethyst): 20 files";
        Safety.Report problems = Safety.assess(in);
        assertEquals("2 save folders have files only their apps can read.", problems.headline);
        assertTrue(problems.detail.startsWith(in.lastRunProblems), problems.detail);
        assertEquals(Safety.Action.SEE_WHAT_TO_DO, problems.action);
        in.unreadableFolders = 1;
        assertEquals("1 save folder has files only its app can read.", Safety.assess(in).headline);

        in.unreadableFolders = 0;
        in.lastRunProblems = null;
        assertEquals("2 save folders need extra access.", Safety.assess(in).headline);

        in.gamesLocked = 0;
        assertEquals("Backups stay on this device only.", Safety.assess(in).headline);

        in.where = Safety.Where.DRIVE;
        assertEquals("EmuBackup can't tell you if a backup fails.", Safety.assess(in).headline);
        assertEquals(Safety.Action.ALLOW_NOTIFICATIONS, Safety.assess(in).action);

        in.notificationsAllowed = true;
        assertEquals(Safety.State.SAFE, Safety.assess(in).state);
    }

    @Test void singularPlural() {
        Safety.Input in = healthy();
        in.gamesStale = 1;
        assertEquals("1 game changed since the last backup.", Safety.assess(in).headline);
    }
}
