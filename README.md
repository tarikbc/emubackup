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

App-private saves work too, through Shizuku, and are verified on hardware: backup, restore,
and permissions matching what the emulator itself writes. Restoring *into* app-private
storage still asks for confirmation, because it is the most invasive thing the app does.

One real limitation: some apps put their saves out of reach of `shell`, which is the identity
Shizuku provides. DuckStation writes mode `600`; Amethyst group-owns its worlds itself. Only
root would read those, and the app does not pretend otherwise — the files are listed in each
backup's manifest and named after a run, never skipped silently.

Google Drive works, over the raw REST API with a `drive.file` scope that can only see the app's
own files. Backups can run on a schedule, daily or weekly, on charge and on Wi-Fi, and old
versions are pruned once there are more than you asked to keep.

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

Google Drive needs your own OAuth client, which you paste into the app under **Google Drive
→ Set up Drive**; no rebuild is involved. See [docs/DRIVE_SETUP.md](docs/DRIVE_SETUP.md).
Without one the app uses a local folder and is fully functional. Release APKs carry no
credentials, by a rule in the workflow rather than by convention, because a published APK
cannot keep an OAuth secret and one shared client would put every user on one project.

## First run

A new install opens a short walkthrough rather than the hub: it explains what the app does,
asks for the one permission it needs, and then shows what it found in **your** saves before
asking for anything else. Destination comes next, Shizuku after that and only when something
is actually locked, and it never blocks. It can be left at any point, and reopened from the
Permissions screen.

On the device this was built for it reads:

    282 MB of saves
    27 save sets, across 14 emulators.

     143 MB  Eden (Nintendo Switch)
      79 MB  Amethyst (Minecraft, Java)
      41 MB  NetherSX2 / ArmsX2 (PlayStation 2)
      12 MB  Azahar (Nintendo 3DS)
       7 MB  PPSSPP (PSP)
    and 9 more

## Where backups go

Three destinations, and you can change between them at any time without losing anything:

- **Google Drive.** Survives losing the device. Needs your own OAuth client, pasted into the
  app, about ten minutes once. See [docs/DRIVE_SETUP.md](docs/DRIVE_SETUP.md).
- **A folder you pick.** An SD card, a USB drive, or a folder owned by a sync app such as
  Dropbox or Nextcloud, in which case the backup leaves the device with no account involved.
  No setup beyond picking it.
- **This device.** A folder in internal storage. It survives an emulator wiping its own data,
  which is the common way saves are lost, and nothing else.

All three write the identical tree, so a version written to one restores exactly like a
version written to another.

## Automatic backups

Daily or weekly, through the framework `JobScheduler`, persisted across reboots, by default only
on charge and on Wi-Fi. The work runs inside the job rather than in a foreground service, because
Android 12 forbids starting one from the background and a schedule that only works while the app
is open is not a schedule.

A job has a bounded runtime, roughly ten minutes. A first backup of several hundred megabytes can
exceed it, so the Automatic backups screen says to run that one by hand; every later run is an
incremental measured in seconds. If the system does stop a job, the attempt is recorded as a
failure with that reason rather than disappearing.

**Three failed scheduled runs in a row turn the schedule off** and raise a notification. A job
retrying quietly forever is worse than no backup, because you believe you are covered. Every run,
manual or scheduled, is in a log on that screen, shareable as plain text.

## What gets deleted, and what never does

Retention keeps the newest N versions, 20 by default. Three things are never removed:

- **The newest version.** A store that prunes itself to empty is not a backup.
- **Pre-restore snapshots**, which exist precisely because something was about to be overwritten.
- **Any version a kept version's chain extracts from.** Deleting the full at the base of a chain
  turns every incremental above it into an archive that restores to nothing, and the failure
  would only appear at the moment the backup was needed.

Each version records which versions its chains depend on, so pruning never has to guess and never
has to download twenty manifests to find out. A version written before this was recorded is
treated as depending on everything older than it, which is the conservative reading and resolves
itself as those versions age out.

## Two things to know

**All-files access.** EmuBackup requests `MANAGE_EXTERNAL_STORAGE` because emulator save
folders are owned by other apps at arbitrary paths, which scoped storage cannot reach. That
permission also makes Play Store distribution impractical, which is fine — this is a
sideloaded APK.

**Shizuku is optional, and it has a ceiling.** Without it you still get every save in shared
storage, which on my device is 700 of the 730 MB. With it you additionally get Dolphin, legacy
Citra, Vita3K, aPS3e, GTA SA and Clone Hero.

You do **not** get DuckStation. It writes its memory cards and save states mode `600`, so only
their owner can read them, and Shizuku grants the `shell` identity rather than root. Minecraft
worlds under Amethyst are group-owned by the app rather than by `ext_data_rw`, which blocks them
the same way. Measured counts for every app-private target are in
[docs/PROVENANCE.md](docs/PROVENANCE.md).

EmuBackup does not paper over this. Unreadable files are listed by name in each manifest's
`skipped` array and named on screen when a run finishes, so an incomplete backup says so instead
of looking like a complete one. Shizuku must also be re-activated after each reboot, and the app
tells you when app-private saves are going stale.

## Credits

[Shizuku](https://github.com/RikkaApps/Shizuku) by RikkaApps for privileged access without
root. The emulator authors whose save layouts this maps.

Not affiliated with any emulator project or with Google. Bring your own games.

## License

[Apache-2.0](LICENSE).

[Privacy policy](PRIVACY.md) and [terms](TERMS.md). Short version: there is no server, no
account and no analytics. Backups go from your device straight to your own Drive, under a
scope that can only see files the app itself created.
