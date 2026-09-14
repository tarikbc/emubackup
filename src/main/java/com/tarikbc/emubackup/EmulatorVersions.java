package com.tarikbc.emubackup;

/**
 * Version of an installed emulator, recorded in every manifest.
 *
 * <p>"Which build wrote this save" is the first question asked when a restored save will not
 * load, so it is captured at backup time rather than guessed later.
 */
public interface EmulatorVersions {

    /** Null when the package is absent. */
    String versionName(String pkg);

    /** Negative when the package is absent. */
    long versionCode(String pkg);

    EmulatorVersions UNKNOWN = new EmulatorVersions() {
        @Override public String versionName(String pkg) { return null; }
        @Override public long versionCode(String pkg) { return -1; }
    };
}
