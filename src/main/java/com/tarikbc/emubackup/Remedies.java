package com.tarikbc.emubackup;

/**
 * What can be done about a save folder whose app writes files nothing else can read.
 *
 * <p>Shizuku runs as the shell user, which reads files that carry the shared group and not
 * files an app keeps to itself (mode 0600, or its own group). Some apps can be told to keep
 * their saves in shared storage; some cannot. This says which, per folder, in the words the
 * sheet shows. Android-free; see {@code test.sh}.
 */
public final class Remedies {

    public static final class Remedy {
        /** Plain words: what to do, or why nothing can be done. */
        public final String text;
        /** Label for the button that opens the app to do it, or null when there is no step. */
        public final String openLabel;

        Remedy(String text, String openLabel) {
            this.text = text;
            this.openLabel = openLabel;
        }

        public boolean fixable() {
            return openLabel != null;
        }
    }

    private Remedies() {}

    public static Remedy forTarget(String targetId, String emulator) {
        switch (targetId) {
            case "duckstation-memcards":
                // Observed on the reference device: a per-game card written by an older build is
                // 0600; the card the current build writes is group-readable. No setting changes
                // an existing file's mode, and DuckStation has none for the folder.
                return new Remedy("DuckStation wrote this card in a way only it can read; the cards "
                        + "it writes now are readable, so this is a card from an older version. "
                        + "There is no setting for it. Set it aside and Home stops raising it; "
                        + "the folder still says it is partly backed up.", null);
            default:
                return new Remedy(emulator + " keeps these files to itself; nothing outside it can "
                        + "read them, and there is no setting to change that. Everything else in "
                        + "this folder is backed up. Set it aside and Home stops raising it; the "
                        + "folder still says it is partly backed up.", null);
        }
    }
}
