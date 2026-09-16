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
                return new Remedy("DuckStation can keep its memory cards where EmuBackup can read "
                        + "them. In DuckStation: Settings, Memory Cards, Directory; choose a folder "
                        + "under ROMs/ps1, then move the cards there once. The next backup will "
                        + "include them.", "Open DuckStation");
            default:
                return new Remedy(emulator + " keeps these files to itself; nothing outside it can "
                        + "read them, and there is no setting to change that. Everything else in "
                        + "this folder is backed up. Set it aside and Home stops raising it; the "
                        + "folder still says it is partly backed up.", null);
        }
    }
}
