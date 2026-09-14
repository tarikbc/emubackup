package com.tarikbc.emubackup;

/**
 * Decides whether a target is written as a full archive or as an increment on an earlier one.
 *
 * <p>The tension is between size and recoverability. Writing everything every time is simple but
 * would re-upload hundreds of megabytes of untouched save states on each run. Chaining forever
 * keeps every version tiny but makes a hand restore an archaeology exercise. So the chain is
 * capped and rebased whenever it stops paying for itself.
 *
 * <p>See {@code FORMAT.md} section 3.
 */
public final class ArchivePolicy {

    /** Hand restore is then never more than this many {@code unzip} commands per target. */
    public static final int MAX_CHAIN = 8;

    /** Rebase once the increments together cost more than half of the full they build on. */
    public static final double REBASE_BYTES_RATIO = 0.5;

    /** Rebase when this much of the target churned; the increment is barely smaller anyway. */
    public static final double REBASE_CHANGED_RATIO = 0.4;

    public static final class Decision {
        public final boolean full;
        /** Shown in the run log and recorded in the manifest, so a rebase never looks arbitrary. */
        public final String reason;

        Decision(boolean full, String reason) {
            this.full = full;
            this.reason = reason;
        }

        public String mode() {
            return full ? "full" : "incremental";
        }
    }

    /**
     * @param chainLength           archives that would have to be extracted, including this one
     * @param lastFullBytes         size of the full archive at the base of the current chain
     * @param cumulativeIncBytes    increments written since that full, excluding this run
     * @param totalFiles            files in the target now
     * @param changedFiles          files being written this run
     */
    public static Decision decide(boolean hasPrior, int chainLength, long lastFullBytes,
                                  long cumulativeIncBytes, int totalFiles, int changedFiles) {
        if (!hasPrior) return new Decision(true, "first backup of this target");
        if (chainLength >= MAX_CHAIN) {
            return new Decision(true, "chain reached " + MAX_CHAIN + " archives");
        }
        if (lastFullBytes > 0 && cumulativeIncBytes > lastFullBytes * REBASE_BYTES_RATIO) {
            return new Decision(true, "increments have grown past half the full archive");
        }
        if (totalFiles > 0 && changedFiles > totalFiles * REBASE_CHANGED_RATIO) {
            return new Decision(true, changedFiles + " of " + totalFiles + " files changed");
        }
        return new Decision(false, "only " + changedFiles + " of " + totalFiles + " files changed");
    }

    private ArchivePolicy() {}
}
