# EmuBackup — design system

Source comments cite this file as `DESIGN.md §N`. If code and this document disagree, the
document is wrong and is corrected in the same commit. It is written for two readers: the
person building a screen, and the person who wants to know why the app feels the way it does.

## §1 Identity

**Who it is for.** Someone with a handheld on the couch. They play Zelda on Eden and Mario
Kart on Dolphin, installed from a video guide. They have lost a save before; everyone has.
They do not know what app-private storage is and should never need to. They hold a gamepad
most of the time and a phone-shaped device some of the time.

**What it promises.** *Your saves are safe, and you can always get one back.* That is the
whole promise. Every screen exists to keep it or to say plainly when it is not being kept.

**The metaphor: a save point.** Not a vault, not a cloud, not a dashboard. A save point is
the one piece of game UI every player already trusts: you walk up to it, it is quick, it does
not make a fuss, and later it is exactly where you left it. The app borrows its manner, not
its imagery. There are no glowing crystals. There is a screen that says "Your saves are
backed up." and means it.

**Personality in three words: calm, plain, certain.**

- *Calm.* One headline, one button. Amber only when something is genuinely stale. No badges
  counting up, no pulsing, no exclamation marks.
- *Plain.* The words a friend would use. "Put back an older save", not "Restore version".
  Numbers over adjectives: "282 MB", "2 hours ago", "17 of 17 games".
- *Certain.* Green means bytes were written and hashed. "Checked" means every checksum
  matched. When the app cannot know, it says so: "Google Drive could not be reached" is a
  different sentence from "No backups yet", and the app never confuses them.

**What gamers respond to** is not gamer styling; it is *competence and respect for their
time*. So the app takes its craft from the system menus of good consoles: dark, big type,
a legend bar that always says what the buttons do, a focus ring you can see from the couch,
instant response. Nothing about it should look like a bank, and nothing about it should look
like an energy drink.

**Voice.** Second person, present tense, short sentences. Say what happened, then what to do.
Never blame ("you forgot to"), never cheer ("Awesome!"), never hedge ("it seems that").

    Yes:  Your saves are backed up.
    Yes:  3 games changed since the last backup.
    Yes:  The last backup could not read everything.
    Yes:  Put back Zelda from Tue 16 Sep, 12:00?
    No:   Success! Your data has been synchronized.
    No:   An error occurred. Please try again later.
    No:   Restore manifest v0008 to target eden-saves?

Three rules that settle most arguments:

1. **Never imply success that has not been verified.** A green tick means bytes were written
   and hashed, not that a request was sent.
2. **Show the number.** "12 of 19 games", "386 MB", "6 days ago". Never "some", "a few", or a
   bar with no count.
3. **Absence is a state worth rendering.** No saves, a locked folder, an unreachable store and
   a folder that vanished are four different things and look different.

## §2 Color

Dark-only. A layered surface ramp carries depth; one accent carries *now*; three hues carry
*outcome*. Tokens live in `res/values/colors.xml`.

    ink_black      #0B0D10   window background
    surface_low    #14171C   the rail, the legend bar, recessed wells
    surface        #1B1F26   cards, rows at rest
    surface_high   #242933   focused rows, raised fields
    hairline       #2E3440   1dp dividers (rare; prefer spacing)

    text_primary   #ECEFF4   headlines, values, row titles         (15:1 on ink)
    text_secondary #9AA4B2   supporting copy, meta                 (6.5:1 on ink)
    text_tertiary  #6B7280   captions, disabled, "cannot"          (3.6:1, never for body)

    accent         #4FC3A1   the focus ring, the primary button, "now"
    accent_deep    #2A8C71   pressed accent

    ok             #5BD68A   safe, verified
    warn           #E3B341   needs you: stale, partial, opt-in with a cost
    danger         #E5534B   problem, destructive confirm, conflict

**The discipline.** Accent means *now* (where focus is, what the one button does). The status
trio means *outcome*. The ramp means *depth*. Never use `ok` as an accent or `accent` as a
result. Amber has exactly two meanings, stale and costly-opt-in, and is never decorative.
Grey (`text_tertiary`) is the fourth status: *cannot*, as in a folder the app cannot see into.

**Two more places colour lives.** The four face buttons on the legend chips carry the colours
printed on the Thor's controller, and only there: `btn_a` red `#E5534B`, `btn_b` yellow
`#E3B341`, `btn_x` blue `#4A9BE8`, `btn_y` green `#5BD68A`. Console badges carry a tint per
console (`Consoles.tint`: Switch red, GameCube violet, Wii light blue, PlayStation blues,
Dreamcast orange, Minecraft green and so on), as the letters on a wash of the same tint at
18% alpha. Neither is ever a status.

Meaning is never carried by colour alone. Every dot has a word next to it ("Safe", "Needs you",
"Problem", "Not set up"), every amber row says why, every badge has its letters.

## §3 Mark and icons

**The mark** is a cartridge: the object a battery save used to live in, which is exactly what
the app preserves. One drawing serves every use. On the 24 grid it is a 2 px round stroke
with a clipped top-right corner, a label window and a grip line, the same weight as the
Lucide icons beside it; that is `ic_mark`, tinted at runtime, on the rail beside the name
and as the cartridge-family glyph on the Games chips. On the launcher the same silhouette is
filled in `accent` with the window and grip cut in `surface_low`, placed in the 66 dp safe
zone of the adaptive canvas (`mipmap-anydpi-v26/ic_launcher.xml`). Chosen from six
candidates on 2026-09-16. Consoles are named with text badges (§6), not logos.

**Icons** are [Lucide](https://lucide.dev) (ISC), vendored as vector drawables by
`tools/vendor-icons.py`: 24 px grid, 2 px round stroke, tinted at runtime through
`Ui.icon` / `Ui.iconStart`. Sizes: 14 dp beside a caption, 16 dp beside a status word, 20 dp
in a row, 22 dp on the rail and tab bar. An icon never stands alone; it sits next to the word
it illustrates. The set in use: house, gamepad-2, archive, settings, clock, cloud, folder,
smartphone, calendar, shield-check, circle-check, triangle-alert, circle-x, info, circle-question-mark,
lock, key, bell, layers, rotate-ccw, hard-drive, refresh-cw, share-2, file-down, list-checks,
save. Add one with the script; do not draw one by hand.

## §4 Type and numbers

Framework families only (Roboto on every device this targets); nothing bundled. Scale:

    hero      30sp bold      the Home headline (portrait: 26sp)
    title     24sp bold      pane title, game name, sheet title (sheet: 22sp)
    row       17sp regular   row titles, button labels (bold on buttons), facts
    body      16sp regular   sentences: detail under a headline, sheet body
    meta      14sp regular   second line of a row, status words, legend
    caption   12sp regular   uppercase, +8% tracking, section labels and fact labels
    mono      13sp monospace status details, paths, ids; Advanced only

Line spacing +3 dp on body and larger. Headlines wrap freely: a 30sp line holds about 26
characters at the Home pane's width, so a headline is one short sentence, never two.

**Numbers in the main flow are proportional**, set in the same face as the words around them:
"282 MB", "3 hours ago". Monospace is for the Advanced status block and for raw identifiers,
where columns must align and hex must be readable. Byte counts use `Sizes.human`: one decimal
below ten units, none above.

Time is a phrase, never a stamp. `Ago.format` for distance ("2 hours ago"), `When.format` for
a moment ("Today, 14:24", "Yesterday, 12:00", "Tue 16 Sep, 12:00"). Version ids never appear
outside Advanced.

## §5 Layout

**Two forms, one app.** The shell picks a form from the window's width and height, on every
configuration change, without reloading anything.

*Wide* (landscape, the Thor: about 835 × 446 dp of app area):

    ┌──────────┬──────────────────────────────────────────────┐
    │ rail     │ pane                                         │
    │ 150 dp   │ master-detail inside where it fits           │
    │ 4 places │                                              │
    │ (dot on  │                                              │
    │  Home)   │                                              │
    ├──────────┴──────────────────────────────────────────────┤
    │ legend bar, 40 dp, only while a gamepad is attached     │
    └─────────────────────────────────────────────────────────┘

*Tall* (portrait, phones and the Thor held upright):

    ┌──────────────────────────┐
    │ pane                     │
    │ one column, scrolls      │
    │                          │
    ├──────────────────────────┤
    │ legend bar (gamepad only)│
    ├──────────────────────────┤
    │ Home · Games · Backups · Settings   56 dp tab bar │
    └──────────────────────────┘

The rail and the tab bar are the same four places, with the status dot on Home in both.
Nothing exists in one form that does not exist in the other; the pane's content reflows, it
does not change.

**Grid.** 4 dp base. Spacing steps: 4, 8, 12, 16, 20, 24, 32. Pane gutter 32 dp wide, 20 dp
tall. Card padding 20 dp. Row padding 18 dp horizontal, 12 dp vertical. Gap between rows
10 dp. Between a headline and its detail 10 dp; between detail and the button 24 dp.

**Radii.** Row and rail item 10 dp. Button 12 dp. Card and sheet 14 dp. Chip and badge pill.

**Sizes.** Row minimum 60 dp. Button 56 dp tall, 200 dp minimum wide. Touch target never
under 48 dp. Focus ring 3 dp. Rail 150 dp. Legend bar 40 dp. Tab bar 56 dp. Sheet width
min(520 dp, width − 48 dp).

**Home reflow.** Wide: status and headline left (60%), facts card and nothing else right
(40%); the button sits under the detail on the left. Tall: status, headline, detail, button,
then the facts card, one column, scrolling.

**Lists reflow.** Wide: a list can open its detail in the same pane (Games → Game page) and
B returns to the list with focus on the row it left. Tall: the same, full width.

## §6 Components

Every component below exists in `Ui`, `SheetView`, `LegendBar` or the pane that owns it.
Build from these; do not hand-roll a button.

- **Rail item / tab.** Icon and text, 16sp on the rail (icon left), 12sp on the tab bar
  (icon above). Selected: accent, bold, icon accent. Focused: `surface_high` fill with the
  ring. At rest: no fill (the rail is a strip, not a stack of buttons).
- **Status dot.** 8 dp circle in the status hue, riding on the Home item of the rail and the
  tab bar, so the state is visible from any pane. The word for it is on Home itself; the dot
  never appears without that word somewhere on screen.
- **Legend bar.** `LegendBar`. Chips for buttons (A, B, X, Y, L1/R1) with a meaning each; the
  face buttons in the controller's own colours (§2) with white letters, as printed on the
  Thor; L1/R1 on `surface_high`. Every chip is a button: tapping it presses that button
  through the same key path, so a hand on the screen can do anything a hand on the pad can.
  Shown only while a gamepad is attached. Every screen sets it; it is never stale.
- **Primary button.** `Ui.primaryButton`. Accent fill, ink text, 17sp bold, 56 dp. One per
  screen. Focused: a light 3 dp ring. Pressed: `accent_deep`.
- **Secondary button.** `Ui.secondaryButton`. `surface` fill, primary text, same size. For
  Cancel and for the second of two actions.
- **Text button.** A `meta`-sized label with the rail-item selector, for "What does this
  mean?" and other light actions. Still 48 dp tall by padding.
- **List row.** `focus_ring` background, 60 dp minimum. Left: an optional console badge.
  Middle: title (row) over meta. Right: a status word in its hue. Rows are the unit of
  navigation, so every row is focusable and clickable; a row is never just information.
- **Header row / caption.** `Ui.caption`. Uppercase, tertiary, 22 dp above, 0 below.
- **Filter chip.** A pill with a family glyph (disc, cartridge, controller; console logos are
  trademarks and are not drawn) and the console's name; selected: accent; focused: ring.
  Chips are focusable, so the D-pad reaches them by moving up from the list; L2/R2 step
  through them without moving the cursor.
- **Console badge.** `Ui.badge` with `Consoles.badge` and `Consoles.tint`. 2–4 letters (SW,
  GC, WII, 3DS, DS, PS1, PS2, PS3, PSP, VITA, DC, RA, MC, AND, CH), 12sp bold, in the
  console's tint on a wash of it. Text, not logos; nothing to license and nothing to draw.
- **Facts card.** `Ui.card` on `surface`, 20 dp padding: caption over value, 14 dp between
  pairs. Four facts on Home, never more.
- **Sheet.** `SheetView`. An in-pane overlay on a 70% scrim; never an `AlertDialog`, which is
  a separate window the gamepad handler cannot see. Title, body, up to two buttons.
  **The safe button takes focus first.** Focus is fenced to the sheet. Tapping the scrim or
  pressing B closes it. The input variant adds one text field and is the only place text
  is typed outside the Drive client screen.
- **Progress.** A full-pane screen: plain-words phase, item name, a determinate bar with the
  count, one button (Stop, then Close). On finish: one-line outcome, every problem named.
- **Category chip** (Advanced, `TargetAdapter`). `SAVE` neutral, `STATE` tertiary, `KEY`
  amber. 13sp, 4 dp radius, no fill for neutral.
- **Status block** (Advanced, `StatusActivity`). The monospace dump, verbatim.

## §7 Motion

Almost none. Focus moves instantly. A sheet appears at once; if anything fades it is the scrim,
120 ms. Progress is the only continuous animation. List updates do not animate: change
animations detach the focused view and lose the cursor (`RecyclerView.setItemAnimator(null)`).
Motion must never delay a number appearing.

## §8 States

Every screen and every list defines these, and a person can tell them apart at a glance:

- **Empty.** A sentence saying why and what to do. Never a bare icon.
- **Unreachable.** "Google Drive could not be reached" with the reason and a retry. Never
  rendered as empty.
- **Cannot.** A folder the app is not allowed into: visible, grey, with the reason and the
  way in ("needs extra access"). Never hidden.
- **Working.** Progress with counts and a Stop that works at once.
- **Failed.** The actual cause in plain words, the detail in full, and the next action.
  Never "Something went wrong".

Home has four and the rail shows which: **Safe**, **Needs you** (amber), **Problem** (red),
**Not set up** (grey). Priority is fixed in `Safety`: the worst wins, one at a time.

**A state the person cannot fix is not a state to nag about forever.** A save folder whose
app keeps files to itself (DuckStation's memory card, Amethyst's world data) is named on
Home with the fix where one exists ("See what to do" opens a sheet with an Open-the-app
button) and, where none exists, a **Set aside** choice: Home stops raising it, the Games row
still says "partly backed up · set aside", and Settings → What to include lists it with
"Include again". The truth stays on screen; the alarm does not.

Interaction states on every focusable thing: **focused** (3 dp accent ring, `surface_high`
fill), **pressed** (`accent_deep`), **selected** (accent text), **disabled** (40% alpha
and a reason nearby). A missing grant never silently no-ops a tap: the control is either
disabled with a visible reason, or tapping it explains and offers the grant.

## §9 Theme and platform

`Theme.EmuBackup` extends `android:Theme.Material.NoActionBar`: framework Material, not
AndroidX Material3, because this is a Gradle-free build with no Material Components library
vendored. Dark-only, deliberately: **no `values-night` variant.** `colorSurface` and
`colorError` are AndroidX-only attributes with no framework equivalent, so they are unset;
surfaces and errors come from the §2 tokens applied directly.

Buttons are `TextView`s with the app's own selectors rather than framework Buttons, because
the framework button shows focus as a faint ripple that is invisible from a couch.

Both orientations are supported. `ShellActivity` handles `orientation|screenSize|screenLayout`
itself and rebuilds its views, so a rotation never re-scans the device or re-reads Drive.

minSdk 26: no `textFontWeight`, no per-app locale APIs; vector drawables are safe. Anything
needing a newer API degrades, never crashes.

## §10 Input

Gamepad first; touch is first-class. Measured facts and rules are in `INPUT.md`; the design
consequences:

| Input | Meaning |
|---|---|
| D-pad, left stick | Move focus. The framework converts both; the app never does. |
| **A** | Activate the focused thing. |
| **B** | Back one level: sheet → detail → list → rail → Home → out. Never traps. |
| **X** | The screen's one secondary action, named in the legend (e.g. "Rename profile"). |
| **Y** | "What is this?" on Home and on Settings sections. |
| L1 / R1 | Previous / next place on the rail, everywhere. |
| L2 / R2 | The pane's own previous / next: the console filter on Games. |

Rules: **nothing is reachable only by a gamepad button**; every X and Y has a visible button.
Focus is always visible; every screen sets a default focus; the first D-pad press must land
somewhere sensible. No long-press, no swipe, no drag, no double-tap. Touch targets are at
least 48 dp. On a phone with no gamepad the legend is hidden and the app is an ordinary,
well-behaved touch app.

## §11 Language

Never in the main flow: target, tier, registry, manifest, chain, sha256, version id, index,
sink, app-private, override, job, Shizuku (except as the product name on its own screen).

| Was | Now |
|---|---|
| target / save set | save folder, or just the game or emulator name |
| version `v0008-20260916-1724` | "the backup from Tue 16 Sep, 14:24" |
| app-private / Tier B | "protected folders": folders only the emulator can see |
| Shizuku | "extra access", set up with Shizuku |
| all-files access | "storage access" |
| manifest / chain / checksum | never shown; "✓ checked" is the outcome |
| restore | "put back" |
| pinned / pre-restore snapshot | "safety copy, kept" |
| locked | "needs extra access" |

## §12 How to build a screen

Answer these before writing a view, and the screen will fit:

1. What is the **one question** this screen answers, in one sentence?
2. What is the **one action**, and where does the primary button sit?
3. Where does **focus start** when the screen appears by gamepad?
4. What does **B** do here, and does it ever trap?
5. What does the **legend** say?
6. What does the screen look like **empty**, **unreachable**, **cannot** and **failed**?
7. How does it **reflow** in the tall form?
8. Is every word in §11's right-hand column?
