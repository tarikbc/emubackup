<div align="center">

# EmuBackup

**Emulator saves, backed up.**

Finds every emulator save on your Android device, versions it, and restores it —
including the ones Android hides from apps.

[![Build](https://github.com/tarikbc/emubackup/actions/workflows/build.yml/badge.svg)](https://github.com/tarikbc/emubackup/actions/workflows/build.yml)

</div>

## Why

An emulator update destroyed my Switch profiles. The saves themselves survived only by
luck — they happened to sit in shared storage rather than inside the app's own folder — and
recovering them meant rebuilding a profile file byte by byte from directory names.

Emulator save data on Android is scattered across a dozen incompatible layouts. Some of it
sits beside multi-gigabyte ROMs. Some of it sits in `Android/data`, where it is deleted on
uninstall and unreadable by ordinary apps. There was no single tool that found all of it.

On my own device, the saves worth keeping came to **730 MB out of 735 GB used** — about a
tenth of a percent. Small, scattered, and irreplaceable.

## What it does

- **Finds saves by an auditable registry**, not guesswork. One JSON file lists every path
  the app will ever touch, validated in CI. See [docs/TARGETS.md](docs/TARGETS.md).
- **Reads `Android/data` on stock, non-rooted devices.** Android 11 closed that directory
  to apps: `MANAGE_EXTERNAL_STORAGE` does not cover it and SAF cannot target it. With
  optional [Shizuku](https://shizuku.rikka.app/), EmuBackup reaches it anyway — which is
  where every GameCube, Wii, PS1 and legacy-Citra save lives. No root required.
- **Backs up per game and per profile.** Restore one game for one profile, not everything.
- **Versioned, incremental.** Only changed files are uploaded.
- **Google Drive or a local folder.**
- **Restores safely.** A mandatory dry-run diff, a snapshot of anything about to be
  overwritten, and saves that are newer on the device are skipped unless you say otherwise.
- **Never locks you in.** Plain zips, a readable manifest, and a `RESTORE.txt` with the exact
  commands to recover everything with nothing but `unzip`. See [docs/FORMAT.md](docs/FORMAT.md).

## Status

Working for shared-storage saves, and verified on a real device: backup, hand-restore with
nothing but `unzip`, in-app restore with a dry-run preview, and per-game breakdown.

App-private saves (Dolphin, DuckStation, legacy Citra, Vita3K, aPS3e, GTA SA, and Eden's
`profiles.dat`) are wired up through Shizuku but not yet verified on hardware. Restoring
*into* app-private storage carries an extra confirmation until it has been.

Still to come: Google Drive, scheduled backups, and retention.

## Design

[docs/DESIGN.md](docs/DESIGN.md) is the design system, written before the code and cited by
section from source comments. Dark-only, dense, and built on the premise that a backup tool
must never imply success it has not verified.

## Under the hood

Deliberately built **without Gradle or Android Studio** — a hand-rolled, plain-SDK
toolchain of three shell scripts:

    ./vendor-libs.sh    curl the AARs it needs straight from Maven
    ./test.sh           javac the android-free classes, run JUnit 5 on a plain JVM
    ./build.sh          aapt2 -> aidl -> javac -> d8 -> zipalign -> apksigner

Plain Java 17, Views and XML, no dependency injection, no coroutines, no Compose. About 45
of the ~80 classes have zero `android.*` imports — including the backup and restore
engines — so the logic that can lose your data is tested end to end on a JVM with no
emulator. `test.sh` enforces that property rather than trusting it.

## Build and run

    git clone https://github.com/tarikbc/emubackup && cd emubackup
    ./vendor-libs.sh
    ./test.sh
    ./build.sh
    adb install -r build/emubackup.apk

Requires JDK 17, the Android SDK with platform 34 and build-tools 35.0.0, and
`ANDROID_HOME` set.

Google Drive needs your own OAuth client; see [docs/DRIVE_SETUP.md](docs/DRIVE_SETUP.md).
Without one the app uses a local folder and is fully functional. Release APKs from CI are
built without secrets and are local-only.

## Two things to know

**All-files access.** EmuBackup requests `MANAGE_EXTERNAL_STORAGE` because emulator save
folders are owned by other apps at arbitrary paths, which scoped storage cannot reach. That
permission also makes Play Store distribution impractical, which is fine — this is a
sideloaded APK.

**Shizuku is optional.** Without it you still get every save in shared storage, which on my
device is 700 of the 730 MB. With it you additionally get Dolphin, DuckStation, legacy
Citra, Vita3K, aPS3e and GTA SA. Shizuku must be re-activated after each reboot, so the app
tells you when app-private saves are going stale.

## Credits

[Shizuku](https://github.com/RikkaApps/Shizuku) by RikkaApps for privileged access without
root. The emulator authors whose save layouts this maps.

Not affiliated with any emulator project or with Google. Bring your own games.

## License

[Apache-2.0](LICENSE).
