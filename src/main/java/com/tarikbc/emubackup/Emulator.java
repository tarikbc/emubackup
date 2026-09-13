package com.tarikbc.emubackup;

import java.util.Collections;
import java.util.List;

/**
 * One emulator and its targets. Immutable.
 *
 * <p>{@link #packages} is ordered most-preferred first. Eden, for instance, ships both a
 * nightly and a stable package under different names, and a device may have either or both.
 * Presence of any of them decides {@link TargetStatus#PKG_NOT_INSTALLED} rather than the
 * target being hidden — an emulator the user has not installed yet should be visible and
 * explained, not absent.
 */
public final class Emulator {

    public final String id;
    public final String label;
    public final List<String> packages;
    public final List<Target> targets;

    Emulator(String id, String label, List<String> packages, List<Target> targets) {
        this.id = id;
        this.label = label;
        this.packages = Collections.unmodifiableList(packages);
        this.targets = Collections.unmodifiableList(targets);
    }

    /** Total of every target's cap; used only for rough UI sizing, never as a real budget. */
    public long maxBytesTotal() {
        long t = 0;
        for (Target x : targets) t += x.maxBytes;
        return t;
    }

    @Override public String toString() {
        return id + " (" + targets.size() + " targets)";
    }
}
