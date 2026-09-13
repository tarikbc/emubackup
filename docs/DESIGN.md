# EmuBackup — design system

Written before implementation. Source comments cite it as `DESIGN.md §N`. If code and
this document disagree, the document is wrong and should be corrected in the same commit.

## §1 Aesthetic direction

The app handles data whose loss is unrecoverable, and it is used rarely and usually in a
hurry. So the tone is **instrument panel, not consumer app**: dense, legible, factual.
Numbers are always visible. Nothing important is behind an animation or a gesture.

Three rules that settle most arguments:

1. **Never imply success that has not been verified.** A green tick means bytes were
   written and hashed, not that a request was sent.
2. **Show the number.** "12 of 19 covered", "386 MB skipped", "last run 6 days ago". Never
   "some", "a few", or an unlabelled progress bar.
3. **Absence is a state worth rendering.** An emulator with no saves, a locked tier, and a
   target whose folder has vanished are three different things and must look different.

## §2 Color

Dark-only. A layered surface ramp carries hierarchy; one accent carries activity; three
hues carry outcome. Tokens live in `res/values/colors.xml`.

    ink_black     #0B0D10   window background
    surface_low   #14171C   recessed wells, code blocks
    surface       #1B1F26   cards, rows
    surface_high  #242933   raised/selected rows
    hairline      #2E3440   1dp dividers and borders

    text_primary  #ECEFF4   values, titles
    text_secondary#9AA4B2   labels, supporting copy
    text_tertiary #6B7280   disabled, placeholder

    accent        #4FC3A1   in progress, selection, primary action
    accent_deep   #2A8C71   pressed accent, gradient far stop

    ok            #5BD68A   verified success
    warn          #E3B341   partial, stale, opt-in caution
    danger        #E5534B   failure, destructive confirm, conflict

**The discipline:** accent means *now*, the status trio means *outcome*, and the surface
ramp means *depth*. Never use `ok` for an accent or `accent` for a result. Amber is
reserved for two specific meanings — data is stale, or the user is enabling something with
a real cost — and never used decoratively.

## §3 Identity

A cartridge outline: the physical object a battery save used to live in, which is exactly
what the app preserves. Flat, two colors, no gradient, legible at 48 px. Defined as a
vector in `res/drawable/ic_launcher.xml`.

## §4 Typography and numbers

The framework families only; nothing is bundled. Scale:

    display   28sp bold      screen title
    heading   20sp medium    section title
    body      15sp regular   supporting copy
    label     13sp medium    row labels, chips
    value     15sp monospace sizes, counts, identifiers, paths

**Numbers and identifiers are always monospaced.** Byte counts in a list must align on the
unit, and a Switch title ID or profile UUID is unreadable in a proportional face. That is
also why `Sizes.human` shows one decimal below ten units and none above — column width
stays stable as values change.

Raw identifiers are rendered one step down from their label, in `text_tertiary`, so a name
and its ID can sit together without the ID competing.

## §5 Layout and spacing

4 dp base grid; use 4/8/12/16/20/28. Screen gutter 20 dp. Card padding 16 dp. Row height
minimum 56 dp, growing for a second line. Dividers are 1 dp `hairline`, inset to the text
column, never full-bleed.

Every screen is a single vertical scroll. No horizontal paging, no bottom navigation, no
drawer. Navigation is Activity to Activity.

## §6 Components

- **Status card.** Top of the hub. Outcome hue on the left edge as a 3 dp bar, headline
  result, then a monospaced detail block. It is the first thing read, so it carries the
  most important unhappy truth, not a greeting.
- **Target row.** Emulator label, target label, category chip, size, file count. Locked
  Tier B rows render at 40% alpha with a lock glyph and keep their size and path visible —
  the user must be able to see what they are missing before deciding.
- **Category chip.** `SAVE` neutral, `STATE` `text_tertiary`, `KEY` amber. 13sp, 4 dp
  radius, no fill for neutral.
- **SizeBarView.** Stacked proportional bar: saves, states, keys, skipped. Doubles as the
  sanity check for a registry mistake — if it ever shows gigabytes, a glob is wrong.
- **ProgressRingView.** Determinate sweep for per-target progress; indeterminate 90° spin
  when length is unknown.
- **Snackbar.** Ported unchanged; the only transient surface.

## §7 Motion

Almost none. 150 ms ease-out for state changes, 0 ms for list updates. Progress is the one
continuous animation. No shared-element transitions, no parallax. Motion must never delay
a number appearing.

## §8 States

Every list and every screen defines four:

- **Empty** — a sentence saying why it is empty and what to do. Never a bare icon.
- **Locked** — visible but dimmed, with the reason and the unlock path. Never hidden.
- **Working** — progress with counts, and a cancel that works immediately.
- **Failed** — the actual cause in monospace, plus the next action. Never "Something went
  wrong".

A missing grant must never silently no-op a tap. Either the control is disabled with a
visible reason, or tapping it explains and offers the grant.

## §9 Theme and platform mapping

`Theme.EmuBackup` extends `android:Theme.Material.NoActionBar` — framework Material, not
AndroidX Material3, because this is a Gradle-free build with no Material Components
library vendored. Dark-only: there is **no `values-night` variant**, deliberately.

`colorSurface` and `colorError` are AndroidX-only attributes with no framework equivalent,
so they are intentionally unset; surfaces and errors come from the tokens in §2 applied
directly.

minSdk is 26, so: no `textFontWeight` (API 28+), no per-app locale APIs, and vector
drawables are safe. Anything needing a newer API must degrade, not crash.
