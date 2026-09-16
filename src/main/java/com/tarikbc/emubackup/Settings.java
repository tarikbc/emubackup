package com.tarikbc.emubackup;

/**
 * What the person has chosen about scheduling and retention.
 *
 * <p>A value object with no storage of its own, so the rules that matter, the defaults and the
 * clamps, are decided once and tested without a device. {@code Prefs} moves it to and from
 * {@code SharedPreferences} and does nothing else.
 *
 * <p>Android-free by design; see {@code test.sh}.
 */
public final class Settings {

    /** How often a backup runs by itself. */
    public enum Frequency {
        OFF(0),
        DAILY(24 * 60 * 60 * 1000L),
        WEEKLY(7 * 24 * 60 * 60 * 1000L);

        public final long intervalMs;

        Frequency(long intervalMs) {
            this.intervalMs = intervalMs;
        }

        public static Frequency parse(String s) {
            if (s == null) return OFF;
            for (Frequency f : values()) if (f.name().equalsIgnoreCase(s)) return f;
            return OFF;
        }
    }

    /** Twenty daily backups is about three weeks of history, at a few MB each after the first. */
    public static final int DEFAULT_KEEP = 20;

    /** Keeping fewer than two means the previous backup is gone the moment a new one lands. */
    public static final int MIN_KEEP = 2;

    public final Frequency frequency;

    /** Off a charger, a full scan plus upload is a noticeable bite out of a handheld's battery. */
    public final boolean requiresCharging;

    /** A first backup is hundreds of megabytes. Sending that over mobile data uninvited is rude. */
    public final boolean requiresUnmetered;

    public final int keepVersions;

    /**
     * Include save states. Off by default because they are large, machine-specific, and easy to
     * recreate by playing; on this device they are 496 MB against 289 MB of real saves.
     */
    public final boolean includeStates;

    /**
     * Include emulator keys. Off by default because they are not save data and, for some
     * emulators, not the user's to redistribute. Backing them up is a deliberate choice.
     */
    public final boolean includeKeys;

    /** A ceiling on the store, or {@link RetentionPolicy#UNLIMITED}. */
    public final long budgetBytes;

    public Settings(Frequency frequency, boolean requiresCharging, boolean requiresUnmetered,
                    int keepVersions, long budgetBytes) {
        this(frequency, requiresCharging, requiresUnmetered, keepVersions, budgetBytes,
                false, false);
    }

    public Settings(Frequency frequency, boolean requiresCharging, boolean requiresUnmetered,
                    int keepVersions, long budgetBytes,
                    boolean includeStates, boolean includeKeys) {
        this.includeStates = includeStates;
        this.includeKeys = includeKeys;
        this.frequency = frequency == null ? Frequency.OFF : frequency;
        this.requiresCharging = requiresCharging;
        this.requiresUnmetered = requiresUnmetered;
        // Clamped rather than rejected: this comes from stored values that an older build, or a
        // hand-edited preferences file, may have written.
        this.keepVersions = keepVersions == RetentionPolicy.UNLIMITED
                ? RetentionPolicy.UNLIMITED : Math.max(MIN_KEEP, keepVersions);
        this.budgetBytes = budgetBytes < 0 ? RetentionPolicy.UNLIMITED : budgetBytes;
    }

    public static Settings defaults() {
        return new Settings(Frequency.OFF, true, true, DEFAULT_KEEP, RetentionPolicy.UNLIMITED,
                false, false);
    }

    public Settings withStates(boolean b) {
        return new Settings(frequency, requiresCharging, requiresUnmetered, keepVersions,
                budgetBytes, b, includeKeys);
    }

    public Settings withKeys(boolean b) {
        return new Settings(frequency, requiresCharging, requiresUnmetered, keepVersions,
                budgetBytes, includeStates, b);
    }

    /**
     * The target ids a run should cover, or null for "whatever the registry enables by default".
     *
     * <p>Null rather than an explicit list when nothing is opted in, so the registry stays the
     * single source of truth for defaults and this method cannot drift from it.
     */
    public java.util.Collection<String> selectedTargets(TargetRegistry registry) {
        if (!includeStates && !includeKeys) return null;
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (Target t : registry.defaultEnabledTargets()) ids.add(t.id);
        for (Target t : registry.allTargets()) {
            if (includeStates && t.category == Category.STATE) ids.add(t.id);
            if (includeKeys && t.category == Category.KEY) ids.add(t.id);
        }
        return ids;
    }

    public Settings withFrequency(Frequency f) {
        return new Settings(f, requiresCharging, requiresUnmetered, keepVersions, budgetBytes,
                includeStates, includeKeys);
    }

    public Settings withCharging(boolean b) {
        return new Settings(frequency, b, requiresUnmetered, keepVersions, budgetBytes,
                includeStates, includeKeys);
    }

    public Settings withUnmetered(boolean b) {
        return new Settings(frequency, requiresCharging, b, keepVersions, budgetBytes,
                includeStates, includeKeys);
    }

    public Settings withKeep(int n) {
        return new Settings(frequency, requiresCharging, requiresUnmetered, n, budgetBytes,
                includeStates, includeKeys);
    }

    public Settings withBudget(long bytes) {
        return new Settings(frequency, requiresCharging, requiresUnmetered, keepVersions, bytes,
                includeStates, includeKeys);
    }

    public boolean scheduled() {
        return frequency != Frequency.OFF;
    }

    public RetentionPolicy retention() {
        return new RetentionPolicy(keepVersions, budgetBytes);
    }

    /** One line for the hub, in the same register as the rest of its status block. */
    public String describeSchedule() {
        if (!scheduled()) return "off, manual backups only";
        StringBuilder b = new StringBuilder(frequency == Frequency.DAILY ? "daily" : "weekly");
        if (requiresCharging && requiresUnmetered) b.append(", on charge and Wi-Fi");
        else if (requiresCharging) b.append(", on charge");
        else if (requiresUnmetered) b.append(", on Wi-Fi");
        else b.append(", any time");
        return b.toString();
    }
}
