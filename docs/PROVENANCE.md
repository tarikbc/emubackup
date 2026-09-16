# Provenance of the target registry

Every path, package name, and file-matching rule in `src/main/assets/targets.json`
was derived from first-hand inspection of the developer's own device. Nothing was
copied from another project's path tables or game databases. This keeps the
Apache-2.0 license on this repository unencumbered.

## Device

    model        AYN Thor (1 TB)
    soc          Snapdragon 8 Gen 2 ("kalama")
    android      13 (API 33)
    build type   user, ro.debuggable=0, no su
    serial       64ff2273
    storage      942 GB usable, 735 GB used at time of inventory

## Method

Inventory taken 2026-09-12 over USB with adb, read-only throughout. The commands
used, in the order used:

    adb shell pm list packages -3
    adb shell dumpsys package <pkg> | grep -E 'versionName|firstInstallTime'
    adb shell ls -l /sdcard/Android/data/
    adb shell find /sdcard/ROMs -maxdepth 3
    adb shell find /sdcard/Android/data/<pkg>/files -maxdepth 4
    adb shell du -sh <path>
    adb shell find <path> -type f -exec stat -c '%s %Y %n' {} \;

No writes, pushes, deletes, or permission changes were made during the inventory.

## Findings that corrected prior assumptions

- **DraStic is not installed.** Only melonDS covers Nintendo DS on this device.
  A registry entry for DraStic would have been dead weight.
- **NetherSX2, ArmsX2 and Azahar have no `/sdcard/Android/data/<pkg>` directory
  at all.** They run entirely out of user-chosen folders in shared storage.
- **There are two independent, non-overlapping 3DS save sets**: Azahar in shared
  storage and a legacy Citra install in app-private storage. They must be
  separate registry targets; merging them would silently restore the wrong data.
- **`/sdcard/RetroArch/system/` holds both ~4 GB of BIOS images and the tiny
  Dreamcast VMU saves.** The registry therefore matches `vmu_save_*.bin` by
  filename with `recursive: false`, and never includes the folder.
- **Eden's saves are not at its default location.** They are at
  `/sdcard/ROMs/switch/saves`, set by a `Paths\save_directory` override in the
  emulator's own `config.ini`.

## Corrections found by running the registry against the device (2026-09-14)

The registry was first written from the inventory notes, then run against the real
filesystem. Three entries were wrong. All three were silent failures — the app would
have reported success while backing up less than the user believed.

- **`retroarch-vmu` found nothing.** The Dreamcast saves are not in
  `RetroArch/system/` but in `RetroArch/system/dc/`, alongside a second pile of BIOS
  images (`dc_boot.bin`, `naomi.zip`, `f355bios.zip`, and more). The target was
  `recursive: false`, so it could never reach them. Now recursive, with the safety
  coming entirely from the filename globs. It finds 4 VMU saves plus `dc_nvmem.bin`,
  640 KB in total, while ignoring 1623 other files in that tree.
- **`ps2-memcards` missed the rollbacks.** The emulator writes `mcd001.ps2.bak` and
  `mcd001.ps2.bak2` into the memory-card folder itself, not into `memcard-backups/`.
  A `*.ps2` glob does not match them. They are redundant right up until the live card
  is corrupt, which is the one moment they matter, so they are now included. This took
  the target from 17 MB to the 33 MB the inventory recorded.
- **`eden-custom-drop` pointed at a folder that does not exist.** No
  `Custom Complete Fighters Savegame` directory is present anywhere under `ROMs/`. The
  target was removed rather than left to report `ROOT_MISSING` forever.

Also noted, deliberately not covered: `ROMs/switch/Mods/` holds two UltraCam mod
folders. Mods change whether a save loads, but they are re-obtainable content rather
than user-generated data, so they stay out of scope alongside the ROMs themselves.

## Measured against the real device (2026-09-14)

Shared-storage targets only, walked through the live filesystem with an independent
implementation of the glob rules in `TARGETS.md`:

    eden-saves             407 files    143 MB      17 ignored
    ps2-memcards             6 files     33 MB
    ps2-memcard-backups      4 files    7.3 MB
    ps2-states              30 files    385 MB      (opt-in)
    ppsspp-saves            52 files    7.0 MB
    ppsspp-states            3 files     11 MB      (opt-in)
    azahar-nand             32 files    877 KB
    azahar-sdmc            110 files     11 MB
    azahar-states            4 files     92 MB      (opt-in)
    melonds-saves            8 files    904 KB     103 ignored (ROM archives)
    retroarch-saves          8 files    832 KB
    retroarch-vmu            5 files    640 KB    1623 ignored (BIOS)
    ------------------------------------------------------------
    total                  669 files    693 MB
      of which saves                    204 MB
      of which states                   488 MB      (opt-in)
    default selection                   197 MB

The ignored counts are as important as the found ones: 1623 BIOS images and 103 ROM
archives sit inside target roots and are correctly excluded by filename.

## App-private storage is not uniformly readable, even with Shizuku (2026-09-16)

Shizuku provides the `shell` identity, which is a member of group `ext_data_rw`. Whether a
given file under `Android/data` can be read therefore depends on the mode the owning app
wrote it with:

    Dolphin      -rw-rw----  u0_a132 ext_data_rw   readable: group bits are set
    DuckStation  -rw-------  u0_a133 ext_data_rw   unreadable: owner only

So DuckStation's memory cards cannot be backed up on a non-rooted device by any means
available here. Root would work; `shell` cannot. Minecraft Java worlds under Amethyst show
the same pattern for `level.dat` and `session.lock` while the game holds them.

This is a fact about the device, not a defect in the app, and it is reported as such: the
affected files are listed in each manifest's `skipped` array and surfaced as problems after
a run. What it did expose was a real defect — a single unreadable file used to abort the
entire backup, discarding archives already written because no manifest was ever produced.
See the `unreadableFileIsSkippedNotFatal` test.

## Restoring into app-private storage, verified (2026-09-16)

Writing into another app's directory was the last unproven part of the design, and the
question was ownership: files created through the privileged service belong to `shell`, not
to the app that owns the folder.

It works, and the reason is the group rather than the owner. Dolphin's own process reports:

    Uid:    10132
    Groups: 1015 1078 1079 3003 9997 20132 50132      (1078 = ext_data_rw)

Every app carries `ext_data_rw` for its own external data, and files under `Android/data`
are group-owned by it. So a file written by `shell` with group read/write is fully
accessible to the owning emulator.

Round trip on real data: a GameCube save was deleted, restored through the app, and came
back byte-identical with its original modification time intact. The restored file now also
matches its siblings' permissions exactly:

    -rw-rw---- u0_a132 ext_data_rw  78-GQPE-SpongeBob00.gci    (written by Dolphin)
    -rw-rw---- shell   ext_data_rw  7D-GHQE-Save1.gci          (written by the restore)

The owner cannot be changed without root and does not need to be.

## Verified footprint

    real save / state / memcard data     ~730 MB
    of which save states                 ~480 MB (386 MB PS2, 92 MB 3DS)
    BIOS and firmware (excluded)         ~4.4 GB
    total device storage used            735 GB

Save data is roughly 0.1% of used storage. The remainder is ROM images, which are
out of scope.

## Game-name database

`GameNames` resolves identifiers to titles from three sources, in priority order:

1. **Derived on device** by `RomIndexer`, which reads *only filenames* from the
   configured ROM roots. Switch dumps already embed `[0100152000022000]`, PS2
   `[SLUS-20946]`, PSP `ULUS10336`, GameCube `[GALE01]`. Nothing is read from
   file contents, so no multi-gigabyte reads occur.
2. **A small seed list** in `src/main/assets/gamedb/seed.json`, hand-written from
   identifiers observed directly on this device. It is not comprehensive and is
   not intended to be.
3. **The raw identifier**, shown monospaced with an affordance to name it.

No third-party game database is bundled or consulted.
