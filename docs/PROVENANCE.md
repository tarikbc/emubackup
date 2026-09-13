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
