# EmuBackup archive format v1

**Normative.** Source comments cite it as `FORMAT.md §N`. This document is a promise to the
user: anything described here can be restored with standard tools and no copy of this app.
A change that breaks hand-restore is a breaking change and requires a `manifestVersion` bump.

## §1 Guarantees

1. Archives are plain **zip**, readable by any unzip.
2. Zip entry paths are **relative to the target's root**, so extraction is a plain `unzip`
   into a directory named in the manifest.
3. Every version directory contains a **`RESTORE.txt`** listing the exact commands, in
   order, to restore it by hand.
4. Every version directory contains **`SHA256SUMS`** in standard `sha256sum -c` format.
5. `manifest.json` is **pretty-printed and human-readable**. A person may read it to
   understand the backup without running anything.

No encryption, no proprietary container, no content-addressed blob naming. Those would all
be technically better in some dimension and all break guarantee 1.

## §2 Layout

    EmuBackup/
      index.json                        version index; atomically replaced
      RESTORE.txt                       general instructions
      v0001-20260912-1432/
        manifest.json
        RESTORE.txt                     this version, with real paths and order
        SHA256SUMS
        eden-saves.full.zip
        eden-profiles.full.zip
      v0002-20260915-0901/
        manifest.json  RESTORE.txt  SHA256SUMS
        eden-saves.inc.zip              only files that changed
        # no eden-profiles zip: unchanged, manifest points back at v0001

Version directory names are `v####-yyyyMMdd-HHmm`. The counter is authoritative for order;
the timestamp is for humans. Archive names are `<targetId>.<full|inc>.zip`.

## §3 Chaining

A target's archive is either `full` or `inc`. An `inc` contains only files whose content
changed since the basis version. To restore a target, extract its `chain` in order into the
target root; last writer wins. This is what a person would do intuitively, which is the
point.

`ArchivePolicy` writes a `full` when: there is no prior manifest, or the chain would exceed
**8** links, or cumulative incremental bytes exceed 50% of the last full, or more than 40%
of files changed. The chain cap of 8 exists so hand-restore is never more than 8 commands
per target.

## §4 Deletions

Files present in the basis but absent now are recorded in the manifest's `deleted` array.
They are **not** represented in the zip. Neither a hand restore nor the app's own restore
removes them: `RestorePlanner` lists files that exist only on the device as
`ORPHAN_ON_DEVICE` and never deletes anything, because a restore that deletes is a restore
that can destroy the very save someone is trying to get back. `RESTORE.txt` says the same:
extracting by hand leaves them in place; remove them yourself for an exact match.

## §5 Timestamps

The zip DOS timestamp has two-second granularity and no timezone. **The manifest is
authoritative** for modification times: `ManifestFile.m` is exact epoch milliseconds.
`ZipEntry.setLastModifiedTime` is also set as a best effort so that a hand restore lands in
the right ballpark, but the diff engine and the app's restore read mtime only from the
manifest. Never compare zip entry times to device times.

## §6 Manifest

    {
      "manifestVersion": 1,
      "version": "v0007",
      "createdAtMs": 1757712000000,
      "createdAtIso": "2026-09-12T18:20:00Z",
      "appVersionName": "1.2.0",
      "registryVersion": 1,
      "registryOverrideSha256": null,
      "device": { "model": "...", "androidSdk": 33, "fingerprint": "..." },
      "extRoot": "/storage/emulated/0",
      "capabilities": { "allFiles": true, "shell": true, "shellUid": 2000 },
      "targets": [ { ... } ]
    }

Per target:

    {
      "id": "eden-saves", "emulator": "eden",
      "tier": "SHARED", "category": "SAVE",
      "root": "/storage/emulated/0/ROMs/switch/saves",
      "status": "ok", "mode": "incremental", "basis": "v0004",
      "archive": "eden-saves.inc.zip",
      "archiveSha256": "…", "archiveBytes": 220160,
      "chain": ["v0004/eden-saves.full.zip", "v0007/eden-saves.inc.zip"],
      "emulatorVersionName": "0.0.3-nightly", "emulatorVersionCode": 312,
      "totals": { "files": 422, "bytes": 149946368, "changedFiles": 3, "changedBytes": 220160 },
      "groups": [ { "profile": "F2551…", "game": "0100152000022000",
                    "displayName": "Mario Kart 8 Deluxe", "files": 14, "bytes": 3211264 } ],
      "files":  [ { "p": "user/save/0000…/F2551…/0100152000022000/save.dat",
                    "s": 65536, "m": 1757600000000, "h": "ab12…", "v": "v0007" } ],
      "deleted": [ "old/path.dat" ],
      "skipped": [ { "p": "…", "reason": "maxBytes" } ],
      "profileUuids": [ "F255133E7DABC494CD4B3089D53DB2DB" ]
    }

`files[].v` names the version whose archive holds those bytes. That field is the entire
chain-resolution mechanism; without it an incremental is not restorable.

`status` is one of `ok`, `empty`, `root_missing`, `pkg_not_installed`, `tier_unavailable`,
`over_cap`. Recording it matters: a later diff must be able to tell "this emulator started
being used" from "this was never present".

Unknown top-level keys must be tolerated by readers. Unknown `manifestVersion` must be
refused rather than guessed.

## §7 Integrity

`SHA256SUMS` covers every archive in the version directory, in `sha256sum -c` format —
lowercase hex, **two spaces**, then the filename. `archiveSha256` in the manifest repeats
the value so a reader needs only one file. SHA-256 is used rather than a faster hash
specifically because `sha256sum -c` exists everywhere.

## §8 index.json

    { "indexVersion": 1, "versions": [
        { "id": "v0007", "createdAtMs": …, "bytes": …, "pinned": false, "kind": "scheduled" } ] }

`kind` is `manual`, `scheduled`, or `prerestore`. Pre-restore snapshots are always
`pinned: true` and are never pruned by retention.

`index.json` is a convenience, not a source of truth: `BackupIndex.rebuildFrom()`
reconstructs it by reading every `v*/manifest.json`. Losing it is recoverable.

## §9 What is never written

- Nothing outside the chosen destination directory or Drive folder.
- No ROM images, ever. The registry allow-lists save paths; there is no code path that
  copies a parent folder.
- No file exceeding its target's `maxBytes`. Such a target is marked `over_cap` and skipped
  with a visible reason. Truncation is never acceptable in a backup.
