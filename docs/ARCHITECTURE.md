# EmuBackup — architecture

> Cited from source comments as `ARCHITECTURE.md`. The normative archive spec is
> `FORMAT.md`; the visual system is `DESIGN.md`; the registry reference is `TARGETS.md`.

## Why this exists

The developer owns an AYN Thor Android handheld with ~25 emulated systems and 40+ Switch games. On 2026-09-12 an Eden emulator update plus a stray file deletion wiped `prod.keys`, reset `config.ini`, and destroyed `profiles.dat`. The game saves survived only because they happened to live in shared storage at `/sdcard/ROMs/switch/saves`. The profiles did not, and had to be rebuilt byte-by-byte from save-folder names to recover 40 games and 113 MB of saves.

That near-miss is the reason for this app. Emulator save data on Android is scattered across a dozen vendor-specific layouts, some of it in app-private storage that vanishes on uninstall, with no unified backup story. EmuBackup finds every emulator save on the device, backs it up on a schedule, versions it, and restores it.

It matches the engineering and design conventions of `~/Programacao/whammy`.

**Repo:** `~/Programacao/emubackup`, package `com.tarikbc.emubackup`, flat. **License: Apache-2.0 in commit 1.**

## Decisions

| Question | Decision |
|---|---|
| Name | **EmuBackup** |
| License | **Apache-2.0**, from the first commit |
| Destination | **Google Drive** primary, local folder fallback |
| Shizuku | **Optional.** Tier A works on first launch; Shizuku unlocks Tier B |
| Restore | **In v1**, with mandatory dry-run preview |
| Scheduling | **Automatic and manual**, both in v1 |
| Save states | **Excluded by default, opt-in per emulator.** When enabled, they **do** upload to Drive |
| Drive auth | **Device authorization grant** — code typed at `google.com/device` |
| Removable SD card | **Not in v1.** `{EXT}` indirection is designed in so it stays additive |

## Hard constraint

The Thor is **stock, non-rooted, Android 13 / API 33**: `ro.build.type=user`, `ro.debuggable=0`, no `su`, Shizuku not installed.

- `MANAGE_EXTERNAL_STORAGE` does **not** grant `/sdcard/Android/data` or `Android/obb`. Google carved those out in Android 11.
- SAF cannot target `Android/data` trees on Android 13.
- Root is unavailable.

So app-private saves need **Shizuku** (activated over adb, giving `shell` UID). This produces a two-tier capability model, and the app must be genuinely useful in Tier A alone.

| Tier | Needs | Covers |
|---|---|---|
| A | `MANAGE_EXTERNAL_STORAGE` | ~700 MB of 730 MB, including the Eden saves that motivated the app |
| B | Shizuku activated | `/sdcard/Android/data/<pkg>/` — all GameCube, Wii, PS1, legacy-Citra, Vita, PS3 and GTA SA saves, plus Eden's `profiles.dat` |

## Save inventory (verified on-device 2026-09-12)

**~730 MB across 19 packages — about 0.1% of the 735 GB used.** The valuable data is tiny, scattered, and fragile.

### Tier A — shared storage

| System | Emulator | Path | Size |
|---|---|---|---|
| Switch | Eden | `/sdcard/ROMs/switch/saves/` (422 files) | 143 MB |
| Switch | Eden | `/sdcard/ROMs/switch/Custom Complete Fighters Savegame/` | 7.9 MB |
| PS2 | NetherSX2 / ArmsX2 | `/sdcard/ROMs/ps2/memcards/` + `memcard-backups/` | 40 MB |
| PS2 | NetherSX2 / ArmsX2 | `/sdcard/ROMs/ps2/sstates/` (30 states) | 386 MB |
| PSP | PPSSPP | `/sdcard/ROMs/psp/PSP/SAVEDATA/` + `PPSSPP_STATE/` | 18 MB |
| 3DS | Azahar | `/sdcard/ROMs/n3ds/{nand,sdmc}` + `states/` | 105 MB |
| DS | melonDS | `/sdcard/ROMs/nds/*.sav` (beside multi-GB ROM zips) | ~0.9 MB |
| Multi | RetroArch | `/sdcard/RetroArch/{saves,states}` | ~1 MB |
| Dreamcast | RetroArch (Flycast) | `/sdcard/RetroArch/system/vmu_save_*.bin` | KB |

### Tier B — app-private, needs Shizuku

| System | Emulator | Path fragment | Size |
|---|---|---|---|
| GC / Wii | Dolphin | `files/GC/USA/Card A/*.gci`, `SRAM.raw`, `files/Wii/title/**/data/save.dat` | 663 KB |
| PS1 | DuckStation | `files/memcards/`, `files/savestates/` | 5.4 MB |
| 3DS | legacy Citra | `files/citra-emu/{nand,sdmc,sysdata}` | ~14 MB |
| Switch | Eden | `files/nand/system/save/` — **holds `profiles.dat`** | 264 KB |
| Vita | Vita3K | `files/vita/ux0/user/00/savedata/` | <100 KB |
| PS3 | aPS3e | `config/dev_hdd0/home/00000001/{savedata,trophy}` | 1.6 MB |
| PS3 | RPCSX | same shape, empty today | 0 |
| Native | GTA SA | `files/GTASAsf*.b`, `gta_sa.set` | small |
| Native | Clone Hero, Minecraft Bedrock, Amethyst | `files/**` | mostly empty today |

### Traps the design must solve

1. **Saves interleaved with ROMs.** PS2, PSP, 3DS, Switch and DS keep saves *inside* the ROM tree; `ROMs/nds/*.sav` sits beside multi-GB ROM zips. Requires an allow-list of subpaths and globs, never "copy the parent folder".
2. **Save states dominate size.** 386 MB PS2 + 92 MB 3DS of the 730 MB total. Real saves are only ~250 MB.
3. **Mixed-purpose directories.** `RetroArch/system/` holds ~4 GB of BIOS *and* the tiny Dreamcast VMU saves.
4. **Two independent 3DS save sets.** Azahar (Tier A) and legacy Citra (Tier B) share nothing.
5. **Emulator-owned rolling backups.** PS2 `memcard-backups/` is PCSX2's own; syncing it compounds duplication.
6. **Non-standard drops.** `Custom Complete Fighters Savegame/` sits outside `saves/`.
7. **Sensitive keys beside saves.** Eden's `prod.keys`, Citra's `aes_keys.txt` — not saves, but a restore without them does not boot.
8. **Installed-but-unused emulators.** RPCSX, Winlator, Minecraft Bedrock hold zero bytes today; wire their paths up anyway.

### Corrections to prior assumptions

- **DraStic is not installed.** Only melonDS covers DS. 
- No "Thor Wayfinder" package; the launcher is `com.odin.odinlauncher`.
- NetherSX2, ArmsX2 and Azahar have **no** `Android/data` folder at all.
- Two minor items unverified (cable dropped): Clone Hero's exact score file, and whether Amethyst has `.minecraft/saves/` yet. Both are Tier B regardless.

## Design principles

1. **Names, not identifiers.** The UI must say *Mario Kart 8 Deluxe* and *Madu*, not `0100152000022000` and `F255133E7DABC494CD4B3089D53DB2DB`.
2. **Per-game granularity.** "Restore only Breath of the Wild for Madu" must be expressible. For Eden the tree is profile-major then game-minor, so the UI needs both axes.
3. **Paths are data, not code.** A declarative registry keeps emulator knowledge in one reviewable, CI-validated table.
4. **Never locked in.** Every backup must be restorable by hand with a plain `unzip` and a readable manifest, with no dependency on this app.

**Clean-room:** every path derives from our own first-hand adb inventory above, so Apache-2.0 is unencumbered.

## Whammy conventions to match

Whammy is deliberately unfashionable and very consistent. Matching it means **not** reaching for the modern default stack.

```
vendor-libs.sh   curl AARs/JARs from dl.google.com + Maven Central -> libs/*.jar
test.sh          javac the android-free classes + JUnit 5 console jar, pure JVM
build.sh         aapt2 compile -> aapt2 link -> javac --release 17 -> d8 -> zipalign -> apksigner
```

- `minSdk 26`, `targetSdk 34`, platform `android-34`, build-tools **35.0.0** pinned. No R8, no Gradle, no `kotlinc`.
- **Plain Java 17. Views and XML, not Compose.** No MVVM, no ViewModel, no DI, no coroutines.
- `ExecutorService` + `Handler(Looper.getMainLooper())`; every callback re-checks `isFinishing()/isDestroyed()`.
- Flat single package. `HttpURLConnection` + `org.json`. No OkHttp/Retrofit/Gson/Glide/WorkManager.
- Activity → Activity via `Intent`. No Fragments.
- Theme extends `android:Theme.Material.NoActionBar`. Dark-only, no `values-night`.
- `docs/DESIGN.md` written **before** implementation, cited by section number in Javadoc.
- JVM-only JUnit 5 over `android.*`-free classes, in a sibling `test/java/` root.
- Conventional Commits; single GitHub Actions workflow; tag push builds, signs, publishes.

---

# Architecture

## 1. The target registry — a bundled JSON asset

`src/main/assets/targets.json`, parsed by an `android.*`-free `TargetRegistry.parse(String)`.

**Why JSON rather than a Java builder:** CI can validate the shipped data. `TargetRegistryTest` reads the literal asset off disk and asserts invariants over it, so a malformed glob, a duplicate id, a `STATE` target left enabled-by-default, or a `KEY` target not marked sensitive **fails `./test.sh` and therefore fails CI**. With a builder, all of those compile. `org.json` is already vendored for tests, so the parser needs no new dependency and has zero `android.*` imports.

Secondary win: a power user can drop `/sdcard/EmuBackup/targets.json` to override the bundled asset when an emulator update moves a path, without waiting for a rebuild. The override's hash is recorded in every manifest.

```json
{
  "registryVersion": 1,
  "emulators": [{
    "id": "eden",
    "label": "Eden (Nintendo Switch)",
    "packages": ["dev.eden.eden_emulator.nightly", "dev.eden.eden_emulator"],
    "targets": [{
      "id": "eden-saves", "label": "Game saves",
      "category": "SAVE", "tier": "SHARED",
      "root": "{EXT}/ROMs/switch/saves",
      "include": ["**"], "exclude": ["**/*.tmp", "**/cache/**"],
      "recursive": true, "maxBytes": 536870912,
      "compress": "deflate", "enabledByDefault": true,
      "critical": false, "sensitive": false,
      "coupledWith": ["eden-profiles"],
      "grouping": { "pattern": "user/save/{account}/{profile}/{game}/**",
                    "gameIdKind": "SWITCH_TITLE_ID", "profileIdKind": "SWITCH_USER_UUID" }
    }]
  }]
}
```

| Field | Meaning |
|---|---|
| `tier` | `SHARED` (java.io) or `APP_PRIVATE` (needs Shizuku) |
| `category` | `SAVE` / `STATE` / `KEY` / `CONFIG`. Drives defaults, retention, size caps, UI labelling |
| `root` | `{EXT}` = external storage root; `{DATA}` = `{EXT}/Android/data/<pkg>` |
| `include` / `exclude` | glob lists, target-relative. `exclude` always wins |
| `recursive` | `false` matches only direct children — **this solves trap 3** |
| `maxBytes` | hard cap; exceeding marks `OVER_CAP` and refuses rather than silently truncating |
| `compress` | `store` for already-compressed state files, so CPU is not burned re-deflating |
| `coupledWith` / `critical` | targets that must restore together; Eden saves ↔ profiles |
| `grouping` | per-game / per-profile decomposition |

### Traps solved as data

| Trap | Solution |
|---|---|
| 1 Saves among ROMs | Every target is `root` + globs. No "copy parent folder" code path exists |
| 2 States dominate | `category: STATE` ⇒ default-off, opt-in per install, and bounded by the store-wide size ceiling rather than a separate count. See "Retention as built". |
| 3 BIOS + VMU mixed | `retroarch-vmu`: `recursive: false`, `include: ["vmu_save_*.bin","dc_nvmem*.bin"]`, `maxBytes: 32 MB` |
| 4 Two 3DS sets | Separate emulators, distinct ids and roots; CI asserts they never share a root prefix |
| 5 PS2 rolling backups | Own target, default-off, with a note explaining it multiplies size |
| 6 Non-standard drop | `eden-custom-drop` with its own root. Exactly why the registry shape exists |
| 7 Keys | `category: KEY`, `sensitive: true`, default-off, amber chip, warned at enable |
| 8 Unused emulators | Declared unconditionally; `ScanEngine` returns `OK` / `EMPTY` / `ROOT_MISSING` / `PKG_NOT_INSTALLED` / `TIER_UNAVAILABLE` / `OVER_CAP`. Zero code change when Vita3K is first used |

## 2. Two-tier storage abstraction

Both interfaces are `android.*`-free:

```java
interface FileSource { List<FileStat> walk(String root, boolean recursive); FileStat stat(String p);
                       InputStream open(String p); boolean available(); }
interface FileSink   { void mkdirs(String p); OutputStream create(String p);  // writes p + ".ebtmp"
                       void commit(String tmp, String fin); void setMtime(String p, long ms);
                       void delete(String p); }
```

**Tier A:** `LocalFileSource` / `LocalFileSink`, plain `java.io.File`, no `android.*` imports — so the entire backup and restore pipeline is JVM-testable against temp directories.

**Tier B: Shizuku `bindUserService` (a privileged AIDL service), not `newProcess`.**

Vendoring cost verified by downloading the artifacts: four AARs (`api`, `provider`, `aidl`, `shared` at 13.1.5), **~60 KB total, all resource-free** — after `./vendor-libs.sh`, `libs/aar/` contains only the four AndroidX entries, so the `--extra-packages` machinery is untouched. Their only other dependency is `androidx.annotation`, already vendored. The assumed `hidden-api` transitive deps do not exist.

**Correction, caught at step 1 by inspecting the jar.** The original design specified `Shizuku.newProcess()` on the reasoning that its `@RestrictTo` annotation is lint-only and therefore harmless in a build that runs no lint. That reasoning does not apply, because in 13.1.5 the method is not merely annotated — it is **`private`**:

```
$ javap -p -cp libs/api-13.1.5.jar rikka.shizuku.Shizuku | grep -i process
  private static rikka.shizuku.ShizukuRemoteProcess newProcess(String[], String[], String);
```

`javac` rejects it outright. Reaching it would need reflection into a method whose removal is already announced. So the shell-command approach is off the table, and `ShellRunner` / `ShellOutputParser` are deleted from the class list.

**The supported route is better anyway.** Verified present and public in the same jar:

```
public static void bindUserService(Shizuku$UserServiceArgs, ServiceConnection);
public static int  peekUserService(Shizuku$UserServiceArgs, ServiceConnection);
public static void unbindUserService(Shizuku$UserServiceArgs, ServiceConnection, boolean);
```

`UserServiceArgs` has a public constructor and public builders; `moe.shizuku.server.IShizukuService` ships in `aidl-13.1.5.jar`; and both `build-tools/35.0.0/aidl` and `platforms/android-34/framework.aidl` are present in the local SDK, so the AIDL step is buildable.

Shizuku loads **our own service class into a `shell`-UID process**, so it uses plain `java.io.File` at shell privilege. That removes a whole category of risk the shell design carried:

| Shell approach | UserService approach |
|---|---|
| Parse `stat -c '%s\|%Y\|%n'` output | `File.length()` / `lastModified()` directly |
| GNU vs BSD `stat` divergence across dev and CI | No divergence; it is Java |
| Shell-quote `Custom Complete Fighters Savegame` | No shell, no quoting |
| Filenames with newlines unparseable | No parsing at all |
| Depends on toybox `find`/`tar`/`sha256sum` | Depends on nothing |
| Deprecated, removal announced, null on some OEMs | Officially supported API |

**Cost:** one `.aidl` interface, an `aidl` step in `build.sh`, a service class, and `bindUserService` lifecycle. Roughly 250 extra lines, which buys a supported API and deletes the parsing layer. Worth it.

**Design:** `IPrivilegedFiles.aidl` exposes exactly what `FileSource`/`FileSink` need — `list(root)` returning a `FileStat[]` parcelable, `openRead(path)` and `openWrite(path)` returning `ParcelFileDescriptor`, `rename`, `delete`, `mkdirs`, `setMtime`, `sha256(path)`, and `uid()` for the probe. Keeping the interface this narrow means the privileged surface is auditable in one short file. `RemoteFileSource` / `RemoteFileSink` (android-free apart from the binder types) implement the seam over it, and `ShizukuGate` owns bind/unbind and the capability probe.

**Manifest additions** — note `aapt2 link` has no placeholder support, so the provider authority is literal, not `${applicationId}.shizuku`:

```xml
<uses-permission android:name="moe.shizuku.manager.permission.API_V23" />
<queries><package android:name="moe.shizuku.privileged.api" /></queries>
<provider android:name="rikka.shizuku.ShizukuProvider"
    android:authorities="com.tarikbc.emubackup.shizuku"
    android:multiprocess="false" android:enabled="true" android:exported="true"
    android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />
```

## 2b. Why Shizuku and not an in-app ADB client

An in-app ADB client was evaluated as a way to avoid asking the user to install a second app.
It is viable and stays possible, but it was not chosen for v1.

The privilege never comes from Shizuku itself. It comes from ADB: the process Shizuku exposes was
started by an ADB command and therefore carries the `shell` identity. An app cannot grant itself
that, by design. So "embedding Shizuku" is not a thing that exists. What *is* possible is speaking
ADB ourselves over Android 11's Wireless Debugging, pairing with the device's own daemon on
localhost.

The only mature Java implementation is `libadb-android`, which is dual-licensed
GPL-3.0-or-later OR Apache-2.0 — so the Apache option keeps this repo unencumbered. Its
dependencies are the problem:

| Library | License | Size |
|---|---|---|
| libadb-android | Apache-2.0 (dual) | small |
| BouncyCastle `bcprov` | MIT-style | ~8 MB |
| Conscrypt | Apache-2.0 | ~4 MB, native `.so` per ABI |
| spake2-java | LGPL-3.0 | small |

That takes a 2.3 MB APK to roughly 15 MB, because this build deliberately has no R8 and ships all
of BouncyCastle whether it is used or not. It introduces the first native dependency into an
otherwise pure-Java build, so `build.sh` would need to place `.so` files in `lib/<abi>/`. And both
crypto components state plainly that they have never been security audited, while handling the
pairing secret.

The decisive point is that it does not even shorten the setup. Both routes need Developer Options,
and both break on reboot:

    Shizuku:     install app, then run one ADB command      (repeat after reboot)
    In-app ADB:  enable Wireless Debugging, read the code,
                 type it in                                  (repeat after reboot, new port)

So it trades one app install for a pairing dance. Shizuku costs four resource-free AARs totalling
about 60 KB and no build changes.

This stays cheap to revisit. Tier B sits behind `FileSource` and `FileSink`; an ADB-backed
implementation would be a third pair beside the local and Shizuku ones, and nothing above that
seam would change.

## 3. Capability model — Shizuku stays optional

```java
final class Capabilities {
  final boolean allFiles;   // Environment.isExternalStorageManager()
  final boolean shell;      // Shizuku READY and probe passed
  final boolean drive;      // OAuth client configured AND valid refresh token
  boolean canRead(Tier t);
}
```

`ScanEngine` and `BackupRunner` take `Capabilities` and emit `TIER_UNAVAILABLE` for Tier B when `!shell` — never an error, never a failed run. Three UI affordances and no more:

1. `ShellActivity` Home: "12 of 19 save sets covered. 7 need Shizuku."
2. `TargetsActivity`: Tier B rows at 40% alpha with a lock glyph, **still showing the declared path and install state** so the user sees what they are missing. This is the upsell.
3. `PermissionActivity`: two stacked cards, with the explicit sentence that Android 11 removed `Android/data` access, `MANAGE_EXTERNAL_STORAGE` does not cover it, SAF cannot target it, and this is where every GameCube, Wii, PS1 and legacy-Citra save lives plus Eden's `profiles.dat`.

**Staleness rule, because Shizuku dies on reboot:** Home shows "App-private saves: last captured 6 days ago", amber past 3 days, red past 7. A scheduled run that finds Shizuku down completes Tier A and reports Tier B as skipped. That failure must never be silent.

## 4. Backup format

Normative spec in `docs/FORMAT.md`, cited by section from Javadoc.

**Per-target zips, chained full + incremental, with a per-version JSON manifest.** Rejected alternatives: one zip per run re-writes 386 MB of unchanged PS2 states every time (10 versions = 7 GB on Drive); a content-addressed blob tree dedups best but turns filenames into hashes, which breaks hand-restore and therefore violates the no-lock-in rule.

```
EmuBackup/
  index.json                      version index; atomic replace
  RESTORE.txt                     how to restore by hand, no app needed
  v0007-20260912-1432/
    manifest.json
    RESTORE.txt                   this version: which zips, which order, which destination
    SHA256SUMS                    `sha256sum -c` compatible
    eden-saves.inc.zip
    eden-profiles.full.zip
    # ps2-memcards absent -> unchanged; manifest points at v0004
```

Zip entries are target-relative, so hand restore is literally `cd /sdcard/ROMs/switch/saves && unzip eden-saves.full.zip`. `RESTORE.txt` prints that command for each target in chain order.

**Chain policy** (`ArchivePolicy`, pure): full when no prior manifest, or chain ≥ 8, or cumulative incremental bytes > 0.5 × last full, or > 40% of files changed. Chains bounded at 8, so hand restore is at most 8 `unzip` commands.

**Manifest** records per target: status, mode, basis version, archive SHA-256, the resolved `chain`, emulator version name and code, totals, per-file `{path, size, mtime, hash, version}`, deletions, skips, and the profile UUIDs present. The per-file `version` is the chain resolution — which zip holds those bytes. ~160 KB per version, negligible against 700 MB, and human-readable by design.

**Zip mtime caveat for `FORMAT.md`:** DOS zip timestamps have 2-second granularity and no timezone. We write `ZipEntry.setLastModifiedTime` *and* record exact epoch millis in the manifest. **The manifest is authoritative**; zip mtime is best-effort so hand restore lands in the right ballpark. `SHA256SUMS` exists so a human can verify with stock `sha256sum -c` on any machine.

## 5. Destination — Drive primary, local fallback

`BackupSink` is an `android.*`-free interface with two implementations producing a **byte-identical tree**. `LocalFolderSink` is what every test exercises and is the no-lock-in guarantee. A "Mirror locally, keep last N" setting defaults to on with N=1, because the motivating incident was local and offline recovery matters more than cloud tidiness.

**Drive v3 over raw `HttpURLConnection` + `org.json`**, exactly the `EncoreApi` idiom. No `google-api-services-drive`, no `play-services-auth`. `DriveApi` therefore has zero `android.*` imports and lands in `test.sh`.

**Scope `drive.file`** — Google classifies it non-sensitive, so no verification and no security assessment. Unlike `drive.appdata`, files stay **visible and downloadable in the user's own Drive UI**, which is the point. Drive has no real directories, so folders are `application/vnd.google-apps.folder` with `parents`; every upload carries `appProperties` so the index can be rebuilt from Drive metadata alone.

**Resumable uploads** — mandatory, because PS2 state archives are ~386 MB:

1. `POST /upload/drive/v3/files?uploadType=resumable` with `X-Upload-Content-Length`; read the session URI from the `Location` header.
2. `PUT <sessionUri>` per chunk with `Content-Range: bytes S-E/T`, `setFixedLengthStreamingMode`. Chunks must be a multiple of 256 KiB; use **8 MiB**.
3. `308` → parse `Range: bytes=0-N`, next start is `N+1`. `200`/`201` → done. `404` → session expired, restart. `5xx`/`429` → backoff, then query with a zero-length `PUT` and `Content-Range: bytes */T`.

Two `HttpURLConnection` traps that must be in code comments: **`setInstanceFollowRedirects(false)` is mandatory** (308 is "Permanent Redirect" and Android's OkHttp-backed stack will follow it and destroy the resume protocol); and use `getErrorStream()` for `>= 400` because `getInputStream()` throws.

The offset decision is extracted as a pure function deliberately mirroring Whammy's `EncoreApi.decideResume`:

```java
static UploadDecision decideUploadResume(int code, String rangeHeader, long attemptedStart, long total);
// kind: CONTINUE | DONE | RESTART_SESSION | RETRY_AFTER_BACKOFF | FAIL
```

**Auth: the OAuth 2.0 device authorization grant.** `DriveAuth` is a one-method interface (`String accessToken()`), so the flow is a swappable seam, but device code is the chosen one and it is a notably good fit here.

1. `POST https://oauth2.googleapis.com/device/code` with `client_id` and `scope=…/auth/drive.file`.
2. Display the returned `user_code` and `verification_url` (`google.com/device`).
3. Poll `POST https://oauth2.googleapis.com/token` with `grant_type=urn:ietf:params:oauth:grant-type:device_code` at the returned `interval`, handling `authorization_pending` and `slow_down`.

`drive.file` is on Google's allow-list for this flow (it permits only `email`, `openid`, `profile`, `drive.appdata`, `drive.file` and two YouTube scopes — we happen to need one of the five).

**Why this is the right call for this project specifically:** Google's Android OAuth client type binds to package name **plus the SHA-1 of the signing certificate**, and `build.sh` generates a throwaway debug keystore *per clone*. A fingerprint-bound client would need re-registering for every contributor and for CI. Google also now disables custom URI schemes by default and advises against them. Device code needs **no redirect URI, no custom scheme, and no fingerprint**, so one client ID works for every build everywhere. On a handheld with no keyboard, typing a short code on a PC also beats typing a Google password on a gamepad.

Two consequences worth noting, both simplifications: **`OAuthRedirectActivity` disappears entirely**, and so does the `__OAUTH_SCHEME__` manifest `sed` step in `build.sh`. The cost is an embedded client "secret", which is public by design for this client type under RFC 8252 — document that plainly in `docs/DRIVE_SETUP.md` rather than pretending otherwise.

**The 7-day trap — the most likely cause of a silently broken backup.** If the OAuth consent screen is left in **Testing** with External user type, Google **revokes refresh tokens after 7 days**, killing scheduled backups weekly. Because `drive.file` is non-sensitive, the project can be moved to **In production** with no verification. `docs/DRIVE_SETUP.md` ends with that step in bold, and `invalid_grant` maps to that exact explanation in the UI.

**Token storage:** refresh token encrypted with a framework-only `AndroidKeyStore` AES-256/GCM key, ciphertext + IV base64'd into `SharedPreferences`. No `androidx.security` AAR for something the framework does in 40 lines. `android:allowBackup="false"`, because the KeyStore key is not backed up and a cloud-restored ciphertext would be undecryptable — this differs from Whammy's `allowBackup="true"`.

**Client ID into the build:** `build.sh` generates `build/gen/com/tarikbc/emubackup/BuildConfigValues.java` from `EMUBACKUP_DRIVE_CLIENT_ID` / `_SECRET` and appends it to `srcs.txt`, mirroring how R.java is already appended. Keeps credentials out of `res/` and out of git. **Empty client id ⇒ `drive == false` ⇒ the app silently defaults to a local folder and is fully functional.** CI sets no secrets, so the published APK is local-only unless you build your own — say so in the release notes.

## 6. Restore

`VersionsActivity` → `RestorePreviewActivity` (dry run, **never skippable**) → `BackupService` in restore mode. The planned `VersionDetailActivity` and `RestoreActivity` were not built; the preview carries both jobs.

`RestorePlanner` (pure) takes the resolved chain, the selected groups, and a fresh scan, and emits one action per file:

| Action | Condition | Default |
|---|---|---|
| `CREATE` | absent on device | apply |
| `SKIP_IDENTICAL` | same size and SHA-256 | skip |
| `OVERWRITE_OLDER` | device mtime ≤ backup, content differs | apply |
| `CONFLICT_NEWER` | **device mtime > backup mtime** | **skip, surfaced loudly** |
| `ORPHAN_ON_DEVICE` | on device, not in backup | leave alone, never delete |
| `BLOCKED_TIER` | Tier B without `shell` | skip, explain |

Overriding `CONFLICT_NEWER` requires a **per-target** toggle showing the count and newest device mtime. There is no global "force everything".

**Pre-restore safety snapshot, non-negotiable.** Before the first byte is written, EmuBackup backs up exactly the files it is about to overwrite into `v####-prerestore-<versionId>/`, pinned so retention never prunes it. Restore refuses to start if the snapshot fails. A tool whose purpose is preventing data loss must not be able to cause it.

**Write discipline:** write `<name>.ebtmp`, fsync, rename. A killed restore leaves stray temp files, never a half-written save.

**Tier B restore is verified on hardware, and the "Experimental" label it was to ship behind was never needed.** Writing into `Android/data/<pkg>/` as shell UID works, and the reason is the group rather than the owner: every app carries `ext_data_rw` for its own external data, and files under `Android/data` are group-owned by it, so a file written by `shell` with group read/write is fully readable by the emulator that owns the folder. A GameCube save was deleted, restored through the app, and came back byte-identical with its modification time intact; see `PROVENANCE.md`. The hard refusal to restore into a Tier B target whose package is not installed, or whose root does not exist, remains, because a tree created wholesale by shell can still get wrong ownership.

**The Eden `profiles.dat` case** — the exact near-miss that motivated the app. Switch saves are keyed to a profile UUID, so a save restored without its profile is orphaned. `eden-saves.coupledWith = ["eden-profiles"]` and `eden-profiles.critical = true`. `ProfileAliases` derives UUIDs **from path components** (`user/save/{account}/{USER_UUID}/{TITLE_ID}/`), not from the binary — robust, and needs no knowledge of the struct layout. It additionally records an opaque SHA-256 of `profiles.dat` and makes a clearly-labelled best-effort nickname parse, wrapped so malformed input cannot throw. Restoring saves without profiles is hard-blocked behind a red warning naming the specific UUID and requiring a non-default dialog button.

**Per-game restore** needs zero format change: a `SaveGroup` is a view over the manifest's file list, so `ArchiveReader` extracts only entries belonging to selected groups, and the zips stay whole for hand restore.

## 7. Scan and diff — stat first, hash only suspects

`DiffEngine.diff(TargetScan, Manifest prior)`:

1. Not in prior ⇒ `ADD`.
2. Same size **and** mtime ⇒ `UNCHANGED`, **no hash read**. The fast path for 386 MB of PS2 states.
3. Size differs ⇒ `CHANGED`, hash it.
4. **Same size, mtime differs ⇒ hash it; if the hash matches, record `UNCHANGED_REHASHED` and archive nothing.** Not an optimisation — emulators rewrite whole save files on exit, so mtimes churn while content does not. Without this, every run re-uploads 150 MB of Eden saves.
5. In prior, absent now ⇒ `DELETED`.

Hashing is proportional to *changed* bytes, so typically a few MB. A separate user-triggered **Verify** action hashes a whole version against the manifest — the integrity check a backup tool owes its user.

## 8. Concurrency, progress, scheduling

`ExecutorService` + `Handler` per Whammy, single-threaded for I/O (parallel writes to one flash device buy nothing and cost determinism — say so in a comment so nobody "optimises" it later).

**The one place EmuBackup needs more than Whammy: a foreground service.** A 730 MB run takes minutes and must survive screen-off. `BackupService` with `foregroundServiceType="dataSync"`, `FOREGROUND_SERVICE_DATA_SYNC` (mandatory at targetSdk 34), a partial wakelock, and `POST_NOTIFICATIONS` requested at runtime on API 33. Activity↔Service link with no DI and no binder: two `static volatile` fields, an immutable `Progress` snapshot the Activity reads on resume, and a listener set in `onResume` / cleared in `onPause`. Rotation and screen-off are then free.

Cancellation is safe at any point because nothing commits until the manifest is written and `index.json` is atomically replaced; a cancelled run leaves an orphan folder the next launch collects.

**Scheduling** via framework `JobScheduler` only: `NETWORK_TYPE_UNMETERED` (Drive sink — do not burn mobile data), `setRequiresCharging(true)`, periodic daily or weekly, `setPersisted(true)`. Two real platform traps: **`setRequiresDeviceIdle(true)` and `setBackoffCriteria` are mutually exclusive** and `build()` throws, so backoff is set only when idle is off and the Settings UI reflects that; and **jobs have a bounded runtime** (~10 min, tightened in Android 14) which a 386 MB upload can exceed.

**Checkpointing** is where resumable upload pays off twice. `RunCheckpoint` persists `{runId, versionId, completedTargetIds, current: {targetId, phase, stagedArchivePath, driveSessionUri, uploadedBytes}}`. `onStopJob` persists and returns `true`; the next run resumes the same version, skips completed targets, and resumes the 386 MB upload **mid-file**.

**Failure reporting**, since the motivating failure mode is a silent one: two notification channels, a `RunLog` readable and shareable as plain text, a red "Last backup" card with the real reason, amber for partial success, and **three consecutive scheduled failures escalate** to a high-importance notification and pause the schedule. A job retrying quietly forever is worse than no backup, because the user believes they are covered.

## 9. Per-game granularity and game names

Grouping is declared per target in the registry:

```json
"grouping": { "pattern": "user/save/{account}/{profile}/{game}/**", "gameIdKind": "SWITCH_TITLE_ID" }
"grouping": { "filenameRegex": "^(?<game>.+)\\.srm$", "gameIdKind": "ROM_BASENAME" }
```

`PathPattern` is a tiny language: `{name}` matches one component and captures it, `**` matches the remainder. ~60 lines, pure, exhaustively testable.

| Target | Grouping |
|---|---|
| Eden Switch | `user/save/{account}/{profile}/{game}/**` — profile-major, game-minor |
| PPSSPP SAVEDATA | `{game}/**`, normalised `ULUS10336DATA00` → `ULUS10336` |
| Azahar / Citra | `Nintendo 3DS/{id0}/{id1}/title/{hi}/{lo}/**` |
| Dolphin GC | `^(?<game>[A-Z0-9]{6})\.gci$` |
| DuckStation states | `^(?<game>[A-Z]{4}-\d{5})_.*\.sav$` |
| RetroArch / melonDS | `^(?<game>.+)\.(srm\|sav\|state\d*)$` |
| PS2 memcards | **`null`** |
| RetroArch VMU | **`null`** |

`grouping: null` is a first-class answer. A PS2 memcard is one opaque binary containing many games; splitting it needs a PS2 filesystem parser and is out of scope. The UI says "whole card only". Honesty beats a fake per-game view that silently restores the wrong thing.

**The game-name database is derived from the user's own ROMs**, which is both better product and simpler than shipping a curated table. Three layers:

1. **Derived.** `RomIndexer` walks ROM roots reading **filenames only** — never contents, so no multi-GB reads. `RomFilenameParser` extracts `[0100152000022000]` for Switch, `SLUS-20946` for PS2, `ULUS10336` for PSP, `[GALE01]` for GameCube, cleaned basenames elsewhere.
2. **Seed.** A couple dozen hand-named entries in assets so the first launch is not entirely raw IDs. Explicitly not comprehensive.
3. **Fallback: the raw ID**, in mono at one step down, with a "Name this game" affordance writing a user alias. Unknown IDs never block, never error, never hide a save.

`GameNames.lookup` returns the raw id rather than null, so no caller can forget the fallback. Profile names get the same treatment: `ProfileAliases` keyed by UUID, seeded by the best-effort nickname parse, freely editable — which is how "Madu" appears in the UI without betting correctness on an unverified binary layout.

## 10. Screens

```
ShellActivity (rail + Home)
 ├─ PermissionActivity      all-files card + Shizuku card
 ├─ TargetsActivity         the registry by emulator; include toggles
 │    └─ GroupsActivity     per-game / per-profile, segmented "By game | By profile"
 ├─ BackupActivity          live run, reads Progress from BackupService
 ├─ VersionsActivity
 │    └─ RestorePreviewActivity  dry-run diff, per-target selection, and Verify
 │         └─ VerifyActivity     reads every archive the chain needs, checks each checksum
 ├─ DestinationActivity    Drive, a picked folder, or this device
 │    └─ DriveLinkActivity      device-code flow
 │         └─ DriveSetupActivity  paste your own OAuth client
 ├─ ScheduleActivity       frequency, conditions, what to include, retention, and the run log
 └─ PermissionActivity     all-files, Shizuku, and the first-run walkthrough again
```

**This tree is what shipped, and it differs from the plan above it.** There is no
`VersionDetailActivity`: Verify hangs off the restore preview, because that is the screen
someone is on when they ask whether a backup is good. There is no `SettingsActivity` or
`LogActivity` either; settings split by subject into `DestinationActivity` and
`ScheduleActivity`, and the run log lives on the schedule screen, where the decision to rely
on a schedule is actually made. `OnboardingActivity` is new and was not planned at all.

Two custom `View`s, matching Whammy's restraint. **`SizeBarView`** is a stacked saves/states/keys/skipped bar — it doubles as the sanity check against trap 1, since a bar suddenly dominated by one segment is visible proof the allow-list broke. **`ProgressRingView`** is adapted from Whammy. `FlowLayout` and `Snackbar` port over unchanged.

## 11. Classes

Flat package `com.tarikbc.emubackup`. **Roughly 80 classes, about 45 of them `android.*`-free.** The two runners being pure is the highest-leverage decision here: backup and restore are testable end-to-end on the JVM with no emulator and no device.

**Pure (in `test.sh`):** `TargetRegistry`, `Emulator`, `Target`, `Grouping`, `PathMatcher`, `PathPattern`, `PathResolver`, `FileSource`/`FileSink`/`FileStat`, `LocalFileSource`/`LocalFileSink`, `RemoteFileSource`/`RemoteFileSink`, `Capabilities`, `ScanEngine`, `DiffEngine`, `GroupBuilder`, `SaveGroup`, `ArchivePolicy`, `ArchiveWriter`, `ArchiveReader`, `Hashes`, `BackupRunner`, `RestoreRunner`, `RestorePlanner`, `Progress`, `RunLog`, `Manifest`, `ManifestTarget`, `ManifestFile`, `BackupIndex`, `IndexEntry`, `VersionId`, `RetentionPolicy`, `VerifyRunner`, `RestoreScript`, `Sizes`, `BackupSink`, `LocalFolderSink`, `DriveApi`, `DriveSink`, `DeviceCodeAuth`, `OAuthClientInput`, `TokenEnvelope`, `JobSpec`, `GameNames`, `RomFilenameParser`, `Settings`.

`RunCheckpoint` was planned and never built; `RunLog` does that job and records every run
rather than only the last. `DriveAuth` became `DeviceCodeAuth`, and `ManifestIO` is folded into
`Manifest` itself. The authoritative list is `PURE_SRCS` in `test.sh`, which CI enforces: a file
listed there that imports `android.*` fails the build.

**Android surface:** the Activities above, plus `BackupService`, `BackupJobService`, `BackupJobScheduler`, `Notifications`, `Prefs`, `Stores`, `Destination`, `DriveClient`, `DriveTokens`, `Onboarding`, `ScanSession`, `RestoreSession`, `AppInfo`, `Assets`, `PrivilegedFileService` (runs as shell UID), `ShizukuGate`, `Permissions`, `TokenStore`, `RomIndexer`, `ProfileAliases`, the adapters, and the custom views.

`ShizukuProbe` and `CapabilityProbe` were planned as separate classes and are folded into
`ShizukuGate` and `Capabilities`. `Stores`, `Destination`,
`DriveClient` and `Onboarding` were not planned and exist because the shipped app needed them.

## 12. Build and CI changes versus Whammy

**`vendor-libs.sh`** — generalise the `androidx()` helper into `aar <repoBase> <path> <artifact> <version> <res|"">`, keep a thin `androidx()` wrapper so the existing lines are untouched, then add four Shizuku lines with `""` for res.

**`build.sh`** — rename package and artifacts; add **`-A src/main/assets`** to `aapt2 link` (required for `targets.json`); generate `BuildConfigValues.java` into `$OUT/gen`; and add an **`aidl` step** before `javac` that compiles `src/main/aidl/**/*.aidl` against `platforms/android-34/framework.aidl` into `$OUT/gen`, where the generated `.java` is already picked up by the existing `find`. Both the `aidl` binary and `framework.aidl` are verified present in the local SDK.

**`test.sh`** — explicit `PURE_SRCS` list plus a guard that fails the build if any listed file gains an `android.` import:

```sh
for f in $PURE_SRCS; do
  grep -q '^import android\.' "$f" && { echo "ERROR: $f is in the JVM test set but imports android.*" >&2; exit 1; }
done
```

That turns "these classes are pure" from a comment into a CI-enforced invariant, which is what the whole test strategy rests on.

**CI** — structurally identical to Whammy's workflow; the Build step's `env:` gains the two Drive secrets, scoped to that step exactly as signing secrets already are. Absent secrets expand to `''` and the build is local-only.

**Docs, written before implementation:** `LICENSE` (Apache-2.0), `docs/DESIGN.md`, `docs/ARCHITECTURE.md`, `docs/FORMAT.md` (normative, the no-lock-in contract), `docs/TARGETS.md` (registry reference and "how to add an emulator"), `docs/DRIVE_SETUP.md`, `docs/THIRD_PARTY.md`.

## 13. Sequencing

1. `LICENSE`, `DESIGN.md`, `FORMAT.md`, `TARGETS.md`; scripts ported, an empty launcher Activity building.
2. Registry + path logic + `TargetRegistryTest` with the full inventory. **Highest value per hour — the moment the registry lands, every trap is locked down by CI.**
3. Tier A source/sink, `ScanEngine`, `TargetsActivity`, `SizeBarView`. First real value: "here is every save on your device, with sizes."
4. `DiffEngine`, `ArchiveWriter`, `Manifest`, `RestoreScript`, `LocalFolderSink`, `BackupRunner`, `BackupService`. Full local Tier A backup.
5. `ArchiveReader`, `RestorePlanner`, `RestoreRunner`, pre-restore snapshot, `RestorePreviewActivity`. Gated by `ArchiveRoundTripTest`.
6. `GroupBuilder`, `PathPattern`, `RomIndexer`, `GameNames`, `GroupsActivity`. Readable per-game UI.
7. Shizuku: gate, privileged service, probe, Tier B backup and restore. The on-device round-trip passed, so no Experimental label shipped.
8. Drive: `DriveApi`, `DriveSink`, auth, `TokenStore`.
9. `BackupJobService`, `RunCheckpoint`, `RetentionPolicy`, notifications, `LogActivity`.
10. Verify, export, registry override, README, screenshots, tag release.

**Steps 1–5 are already a useful app that solves the motivating incident**, with no Shizuku and no Drive.

---

# Verification

**JVM suite (`./test.sh`, runs in CI on every push):**

- `TargetRegistryTest` parses the literal shipped `targets.json` and gates every trap: no duplicate ids; every `{DATA}` target declares a package and `APP_PRIVATE`; **every `STATE` target is default-off**; **every `KEY` target is sensitive and default-off**; Azahar and Citra never share a root prefix; `retroarch-vmu` has `recursive: false` and no `**`; every `coupledWith` resolves; every pattern and regex compiles.
- `PathMatcherTest` — `vmu_save_A1.bin` matches while `dc_boot.bin` and `naomi.zip` do not; `*.sav` matches beside a 4 GB `.zip`; exclude beats include; `recursive:false` ignores grandchildren.
- `ShellQuoteTest` — spaces (`Custom Complete Fighters Savegame`), quotes, `$`, backticks; rejects newline.
- `DiffEngineTest` — same size+mtime ⇒ zero hash calls (counted via a fake source); same size, different mtime, same hash ⇒ nothing archived.
- `ArchiveRoundTripTest` — build a full plus three incrementals over a mutating temp tree, then restore twice: once through `RestoreRunner`, and once **by hand simulation** using only `java.util.zip.ZipInputStream` in the order `RESTORE.txt` states. Both must produce byte-identical trees. This is the no-lock-in promise as an executable assertion.
- `RestorePlannerTest` — `CONFLICT_NEWER` skipped unless forced; orphans never deleted.
- `DriveApiTest` — `decideUploadResume` table over `(308,"bytes=0-8388607")`, `(308,null)`, `(200,null)`, `(404)`, `(503)`, a backwards `Range`, and `Range` end ≥ total. No live HTTP, matching Whammy's suite.
- Plus `PathPatternTest`, `ScanEngineTest`, `GroupBuilderTest`, `ArchivePolicyTest`, `RetentionPolicyTest`, `ProfileFingerprintTest`, `RestoreScriptTest`, `ManifestJsonTest`, `GameNamesTest`, `ShellOutputParserTest` (device-captured fixtures, not the host shell, since `stat -c` is GNU and macOS is BSD).

**On-device, manual, after step 3:** install, grant all-files, confirm `TargetsActivity` lists every Tier A target with sizes matching the adb inventory in this document (Eden 143 MB / 422 files is the reference figure).

**On-device, after step 5:** back up Eden saves, delete a single game's save folder, restore only that group, and confirm Eden still loads it. Then the harder case — rename `profiles.dat`, attempt a saves-only restore, and confirm the red coupling warning fires.

**On-device, after step 7:** confirm the privileged service reports uid 2000, and that Dolphin's `.gci` files appear. Both done, along with the write round-trip; see `PROVENANCE.md`.

**On-device, after step 8:** link Drive, back up with `ps2-states` enabled to force a ~386 MB resumable upload, kill the app mid-upload, and confirm it resumes mid-file rather than restarting.

---

# Risks and open questions

1. **Shizuku must be re-activated after every reboot**, so Tier B silently goes stale. Mitigated by the staleness rule in section 3 (amber past 3 days, red past 7) and by scheduled runs reporting Tier B as skipped rather than failing. *The earlier `newProcess` deprecation risk is resolved: we use the supported `bindUserService` API instead.*
2. ~~Tier B restore file ownership is unverified on this device.~~ **Resolved 2026-09-16.** Verified by round-trip on real data; the mechanism is the `ext_data_rw` group, not the owner. Refusal to restore into a non-installed package remains.
3. **A silently wrong allow-list** — either 4 GB of BIOS in the archive, or a save the user believes is covered and is not. This is the failure that would make EmuBackup worse than nothing. Mitigated by the CI registry gates, `OVER_CAP` refusing rather than truncating, per-target byte counts on the pre-run screen, and `SizeBarView` making a blowout visible.
4. **Google OAuth "Testing" status revoking refresh tokens every 7 days.** Mitigated by the bold setup step, the `invalid_grant` error mapping, and the three-failure escalation.
5. **`profiles.dat` semantics are unverified.** Mitigated by reading UUIDs from path components rather than the binary, and making aliases user-editable.
6. **Removable SD cards are out of scope for v1 by decision.** A removable card cannot be written via `java.io.File` on Android 13 at all; it needs a third SAF-backed `FileSink` with different semantics. The `{EXT}` path indirection exists from day one so adding it later is additive, not a rewrite. Document the limitation in the README.
7. **`prod.keys` stored in plaintext** when the `KEY` category is enabled. Off by default, amber-labelled, warned at enable. Encrypting it would break the hand-unzip guarantee for exactly those files, so the honest default is better than partial crypto.
8. **`MANAGE_EXTERNAL_STORAGE` makes Play Store distribution impractical.** Irrelevant for a sideloaded open-source APK, but note it in the README.

## Drive quota, since states do upload

States are off by default, but when enabled they go to Drive like everything else. The numbers, so retention can be set deliberately:

- A **full** version with states is ~730 MB; without, ~250 MB.
- Free Drive is 15 GB, so ~20 full versions with states, or ~60 without.
- Per-file incrementals keep the common case small. PS2 states are 30 files at ~13 MB, so a session that touches three states costs ~40 MB, not 386 MB.
- The real cost driver is the periodic re-base to a new full, which `ArchivePolicy` triggers at chain length 8 or 50% drift.

**Retention as built:** a version count, default 20, and an optional total size ceiling, both on the whole store. Pre-restore snapshots are pinned forever, the newest version is never removed, and `RetentionPolicy` never prunes a version that a kept chain still extracts from.

**This deviates from the separate per-category counts specified above, deliberately.** Pruning save states out of a version while keeping its saves means deleting individual archives from a version that has already been written, marking them in its manifest, re-uploading that manifest, and teaching restore and verify to expect a version that is intentionally incomplete. That is a format change and a new class of partially-valid backup, in the component whose entire job is to not lose data.

The size ceiling solves the problem the per-category count was for. States are the only thing large enough to make a count-based limit dangerous, and a ceiling bounds them directly and understandably: "keep 20 versions, and at most 10 GB". The Automatic backups screen warns when states are enabled with no ceiling set, quoting what a full backup of them costs on this device.

**Resolved:** removable SD card is out of v1; Drive auth is the device-code flow; states upload to Drive. No open questions block implementation.
