package com.tarikbc.emubackup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ScheduleTest {

    // ---- Settings ----

    @Test
    @DisplayName("a new install schedules nothing until asked")
    void defaultsAreOff() {
        Settings s = Settings.defaults();
        assertFalse(s.scheduled());
        assertNull(JobSpec.of(s));
        assertEquals(Settings.DEFAULT_KEEP, s.keepVersions);
    }

    @Test
    @DisplayName("a keep count below two is raised, because one is no history at all")
    void keepIsClamped() {
        assertEquals(Settings.MIN_KEEP, Settings.defaults().withKeep(1).keepVersions);
        assertEquals(Settings.MIN_KEEP, Settings.defaults().withKeep(0).keepVersions);
        assertEquals(50, Settings.defaults().withKeep(50).keepVersions);
    }

    @Test
    @DisplayName("unlimited survives the clamp instead of becoming two")
    void unlimitedKeepIsNotClamped() {
        Settings s = Settings.defaults().withKeep(RetentionPolicy.UNLIMITED);
        assertEquals(RetentionPolicy.UNLIMITED, s.keepVersions);
        // And it must still behave as unlimited once it reaches the policy.
        List<IndexEntry> five = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            five.add(new IndexEntry("v000" + i + "-20260101-0000", i * 1000L, 10, false,
                    "scheduled", new ArrayList<>()));
        }
        assertTrue(s.retention().toDelete(five).isEmpty());
    }

    @Test
    @DisplayName("an unreadable stored frequency falls back to off, not to a surprise schedule")
    void frequencyParsing() {
        assertEquals(Settings.Frequency.DAILY, Settings.Frequency.parse("DAILY"));
        assertEquals(Settings.Frequency.WEEKLY, Settings.Frequency.parse("weekly"));
        assertEquals(Settings.Frequency.OFF, Settings.Frequency.parse("fortnightly"));
        assertEquals(Settings.Frequency.OFF, Settings.Frequency.parse(null));
    }

    @Test void scheduleDescription() {
        Settings s = Settings.defaults();
        assertEquals("off, manual backups only", s.describeSchedule());
        assertEquals("daily, on charge and Wi-Fi",
                s.withFrequency(Settings.Frequency.DAILY).describeSchedule());
        assertEquals("weekly, on Wi-Fi", s.withFrequency(Settings.Frequency.WEEKLY)
                .withCharging(false).describeSchedule());
        assertEquals("daily, any time", s.withFrequency(Settings.Frequency.DAILY)
                .withCharging(false).withUnmetered(false).describeSchedule());
    }

    // ---- JobSpec ----

    @Test
    @DisplayName("constraints reach the job unchanged")
    void specCarriesConstraints() {
        JobSpec j = JobSpec.of(Settings.defaults().withFrequency(Settings.Frequency.DAILY));
        assertNotNull(j);
        assertEquals(24 * 60 * 60 * 1000L, j.periodMs);
        assertTrue(j.requiresCharging);
        assertTrue(j.requiresUnmetered);
    }

    @Test
    @DisplayName("the period never goes below the framework floor")
    void periodFloor() {
        JobSpec j = JobSpec.of(Settings.defaults().withFrequency(Settings.Frequency.WEEKLY));
        assertTrue(j.periodMs >= JobSpec.MIN_PERIOD_MS);
        assertTrue(j.flexMs >= JobSpec.MIN_PERIOD_MS);
        // Flex must not exceed the period, or the job has no window to speak of.
        assertTrue(j.flexMs <= j.periodMs);
    }

    // ---- RunLog ----

    private static RunLog.Run run(long at, String kind, boolean ok) {
        return new RunLog.Run(at, kind, ok, ok ? "v0001-20260101-0000" : null, 100, ok ? "" : "no network");
    }

    @Test
    @DisplayName("the newest run is the one shown")
    void newestFirst() {
        RunLog log = RunLog.empty()
                .with(run(1000, "manual", true))
                .with(run(3000, "scheduled", false))
                .with(run(2000, "scheduled", true));
        assertEquals(3000, log.last().atMs);
    }

    @Test
    @DisplayName("three scheduled failures in a row escalate, two do not")
    void escalation() {
        RunLog log = RunLog.empty()
                .with(run(1000, "scheduled", true))
                .with(run(2000, "scheduled", false))
                .with(run(3000, "scheduled", false));
        assertEquals(2, log.consecutiveFailures());
        assertFalse(log.shouldEscalate());

        log = log.with(run(4000, "scheduled", false));
        assertEquals(3, log.consecutiveFailures());
        assertTrue(log.shouldEscalate());
    }

    @Test
    @DisplayName("a manual failure does not raise the alarm the schedule owns")
    void manualFailuresDoNotEscalate() {
        RunLog log = RunLog.empty()
                .with(run(1000, "manual", false))
                .with(run(2000, "manual", false))
                .with(run(3000, "manual", false));
        assertEquals(0, log.consecutiveFailures());
        assertFalse(log.shouldEscalate());
    }

    @Test
    @DisplayName("a success clears the count")
    void successResets() {
        RunLog log = RunLog.empty()
                .with(run(1000, "scheduled", false))
                .with(run(2000, "scheduled", false))
                .with(run(3000, "scheduled", true));
        assertEquals(0, log.consecutiveFailures());
    }

    @Test
    @DisplayName("the log stays bounded and drops the oldest")
    void bounded() {
        RunLog log = RunLog.empty();
        for (int i = 1; i <= RunLog.MAX_ENTRIES + 10; i++) log = log.with(run(i * 1000L, "scheduled", true));
        assertEquals(RunLog.MAX_ENTRIES, log.runs().size());
        assertEquals((RunLog.MAX_ENTRIES + 10) * 1000L, log.last().atMs);
    }

    @Test
    @DisplayName("the log survives a round trip through storage")
    void jsonRoundTrip() {
        RunLog log = RunLog.empty()
                .with(run(1000, "scheduled", true))
                .with(new RunLog.Run(2000, "scheduled", false, null, 0, "Drive said invalid_grant"));
        RunLog back = RunLog.fromJson(log.toJson());
        assertEquals(2, back.runs().size());
        assertEquals("Drive said invalid_grant", back.last().detail);
        assertFalse(back.last().ok);
        assertNull(back.last().versionId);
    }

    @Test
    @DisplayName("a corrupt log costs history, never a backup")
    void corruptJsonIsSurvivable() {
        assertEquals(0, RunLog.fromJson("{not json").runs().size());
        assertEquals(0, RunLog.fromJson("").runs().size());
        assertEquals(0, RunLog.fromJson(null).runs().size());
    }

    @Test void plainTextIsReadable() {
        String text = RunLog.empty().with(run(0, "scheduled", false)).toPlainText();
        assertTrue(text.contains("FAILED"), text);
        assertTrue(text.contains("no network"), text);
    }
}
