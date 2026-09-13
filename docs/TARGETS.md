# The target registry

`src/main/assets/targets.json` is the complete list of what EmuBackup reads and writes.
Paths are **data, not code**: adding an emulator is a JSON change, and `TargetRegistryTest`
validates the shipped file on every CI run, so a mistake fails the build rather than
shipping.

A user can override the bundled registry by placing a file at
`<external storage>/EmuBackup/targets.local.json`. That exists because emulators move their
save paths without warning, and waiting for a release is the wrong answer when your data is
at stake. The override's SHA-256 is recorded in every manifest so a backup is always
traceable to the rules that produced it.

## Emulator fields

| Field | Required | Meaning |
|---|---|---|
| `id` | yes | Stable slug. Never renamed; manifests reference it |
| `label` | yes | Shown in the UI, e.g. `Eden (Nintendo Switch)` |
| `packages` | yes | Candidate package names, most preferred first. Presence decides `pkg_not_installed` |
| `targets` | yes | One or more target objects |

## Target fields

| Field | Required | Default | Meaning |
|---|---|---|---|
| `id` | yes | | Stable slug, unique across the whole registry |
| `label` | yes | | Shown in the UI |
| `category` | yes | | `SAVE` \| `STATE` \| `KEY` \| `CONFIG` |
| `tier` | yes | | `SHARED` (java.io) \| `APP_PRIVATE` (needs Shizuku) |
| `pkg` | if `APP_PRIVATE` | | Which package's `Android/data` directory `{DATA}` resolves to |
| `root` | yes | | Must begin `{EXT}` or `{DATA}` |
| `include` | yes | | Glob list, target-relative. Must be non-empty |
| `exclude` | no | `[]` | Glob list. Always beats `include` |
| `recursive` | no | `true` | `false` matches only direct children |
| `caseInsensitive` | no | `false` | For exFAT volumes |
| `maxBytes` | yes | | Hard cap. Exceeding marks `over_cap` and archives nothing |
| `compress` | no | `deflate` | `store` for already-compressed payloads |
| `enabledByDefault` | no | `true` | Must be `false` for `STATE` and `KEY` |
| `sensitive` | no | `false` | Must be `true` for `KEY`. Amber UI treatment |
| `critical` | no | `false` | Restoring the group without this is likely worthless |
| `coupledWith` | no | `[]` | Target ids that must restore together |
| `grouping` | no | `null` | Per-game decomposition, below |
| `note` | no | | Shown in the UI. Explain non-obvious choices here |

### Path variables

- `{EXT}` — primary external storage root, e.g. `/storage/emulated/0`
- `{DATA}` — `{EXT}/Android/data/<pkg>`, requiring `pkg`

Never write a literal `/sdcard`. The indirection is what keeps a future SD-card sink additive.

### Globs

`*` matches within one path component, `**` matches across components, `?` matches one
character. Matching is against the target-relative path with `/` separators.

    "include": ["**"]                       everything under root
    "include": ["*.keys"]                   direct children only, with recursive:false
    "include": ["vmu_save_*.bin"]           filename match, folder never included
    "exclude": ["**/cache/**", "**/*.tmp"]  carve-outs

### Grouping

Either form, or `null`:

    "grouping": { "pattern": "user/save/{account}/{profile}/{game}/**",
                  "gameIdKind": "SWITCH_TITLE_ID", "profileIdKind": "SWITCH_USER_UUID" }

    "grouping": { "filenameRegex": "^(?<game>.+)\\.srm$", "gameIdKind": "ROM_BASENAME" }

`{name}` matches exactly one path component and captures it; `**` matches the remainder, and
may only appear last — a `**` in the middle would make the match ambiguous, and an ambiguous
grouping rule silently mislabels saves.

`pattern` matches the **target-relative path**. `filenameRegex` matches the **basename only**,
and must contain a named group `(?<game>...)`.

**`null` is a legitimate answer, not an omission.** A PS2 memory card is one opaque binary
containing every game's saves; splitting it would need a PS2 filesystem parser. Say so in
`note` and offer whole-target only. A fake per-game view that silently restores the wrong
data is far worse than an honest one.

`gameIdKind` values: `SWITCH_TITLE_ID`, `PSP_GAME_ID`, `PSX_SERIAL`, `PS2_SERIAL`,
`GC_GAME_ID`, `N3DS_TITLE_ID`, `ROM_BASENAME`.

## Invariants enforced by CI

`TargetRegistryTest` fails the build if any of these break:

- No duplicate target or emulator `id`.
- Every `root` begins with `{EXT}` or `{DATA}`; every `{DATA}` target declares `pkg` and is
  `tier: APP_PRIVATE`.
- Every `include` is non-empty.
- **Every `STATE` target has `enabledByDefault: false`.**
- **Every `KEY` target has `enabledByDefault: false` and `sensitive: true`.**
- No two targets share a root prefix across different emulators (catches accidentally
  merging the two independent 3DS save sets).
- Every `coupledWith` id resolves to a real target.
- Every `grouping.pattern` and `filenameRegex` compiles.
- No `include` containing `**` on a target with `recursive: false`.

## Adding an emulator

1. Find the real paths on a device, read-only:

       adb shell find /sdcard/Android/data/<pkg>/files -maxdepth 4
       adb shell du -sh <candidate>

2. Add the emulator block to `targets.json`. Separate targets per category — saves, states,
   and keys are never one target.
3. Set `maxBytes` from the observed size with headroom, not from a guess.
4. Add the emulator to the expected-coverage list in `TargetRegistryTest`.
5. Record what you observed in `docs/PROVENANCE.md`.
6. Run `./test.sh`.

Do not copy paths from other projects. See `docs/PROVENANCE.md` for why.
