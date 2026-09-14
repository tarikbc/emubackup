package com.tarikbc.emubackup;

/**
 * Whether a package is installed.
 *
 * <p>An interface rather than a direct {@code PackageManager} call so that {@link ScanEngine}
 * stays android-free and the "emulator not installed" branch is testable on a JVM.
 */
public interface PackagePresence {

    boolean isInstalled(String pkg);

    /** Assumes everything is present. Useful in tests and when scanning shared storage only. */
    PackagePresence ALL_PRESENT = pkg -> true;
}
