# Input: gamepad and touch

Measured on the AYN Thor (Android 13) with a throwaway probe screen on 2026-09-16, then
encoded in `GamepadActivity`. Every screen extends that class.

## What the controller sends

The built-in controller identifies as "Odin Controller" with the vendor layout
`/system/usr/keylayout/Vendor_2020_Product_0111.kl`.

| Control | Arrives as | Handled by |
|---|---|---|
| A, B, X, Y, L1, R1, L2, R2 | raw `KEYCODE_BUTTON_*`, source `0x501` (gamepad+keyboard) | the app |
| D-pad | hat axes `HAT_X`/`HAT_Y`, which the **framework** converts to `KEYCODE_DPAD_*` with `FLAG_FALLBACK`, source joystick | the framework |
| Left stick | axes `X`/`Y`, which the **framework** converts to `KEYCODE_DPAD_*` with `FLAG_FALLBACK` | the framework |

Two consequences:

1. **A does not activate and B does not go back on their own.** Android's focus system treats
   only `DPAD_CENTER` and `ENTER` as confirm and only `BACK` as back. `GamepadActivity`
   re-dispatches `BUTTON_A` as `DPAD_CENTER` (so pressed visuals and RecyclerView row clicks
   work) and calls `onBackPressed()` when `BUTTON_B` is released.
2. **The app must not convert the stick itself.** `ViewRootImpl`'s synthetic joystick handler
   already does, for both the hat and the stick. Converting again moves focus twice per nudge.
   Events carrying `FLAG_FALLBACK` are passed straight through.

A probe log excerpt, for the record:

    key KEYCODE_BUTTON_A down src=0x501
    key KEYCODE_DPAD_DOWN down FALLBACK src=0x1000010
    stick x=-1.00 y=-0.38 hatX=0.00 hatY=0.00
    stick x=0.00 y=0.00 hatX=0.00 hatY=-1.00

## Rules every screen follows

- **Focus is always visible.** One drawable, `focus_ring.xml`, on every focusable view. On a
  gamepad the ring is the cursor; if it disappears the person is lost.
- **Every screen sets a default focus** (`focusByDefault`), so the first D-pad press lands
  somewhere sensible rather than nowhere.
- **The legend bar** along the bottom says what A, B, X, Y, L1, R1 do on this screen. It is
  shown only while a gamepad is attached (`InputManager` device list, refreshed on change), so
  a phone user never sees chips for buttons they do not have.
- **Nothing is reachable only through a gamepad button.** X, Y, L1 and R1 are accelerators. Every
  action they trigger is also a visible, tappable control of at least 48dp. Touch is a first-
  class way to use the app, not a fallback.
- No long-press, no swipe, no drag, anywhere. Both input methods get the same simple model.
- Confirmation sheets are drawn in the pane, not as `AlertDialog`s: a dialog is a separate
  window and never sees the activity's key mapping.
- After a RecyclerView adapter swap, focus is restored to the previous position; change
  animations are disabled so `notifyItemChanged` does not detach the focused row.

## Verifying without hands

Once the screen is unlocked (`wm dismiss-keyguard`, then `input keyevent 82`), synthetic keys
reach the app and `screencap` returns the main display:

```
adb -s 64ff2273 shell input keyevent KEYCODE_DPAD_DOWN
adb -s 64ff2273 shell input keyevent KEYCODE_BUTTON_A     # arrives with src=0x0, still remapped
adb -s 64ff2273 exec-out screencap -p > shot.png
```

Synthetic events carry source 0 rather than 0x501, so they exercise the remap in
`GamepadActivity` but not gamepad detection: the legend shows only because the real controller
is attached. Quit SideMount first (it contends for the USB connection) and use the serial;
the Thor exposes two adb devices.

The Thor's panel stays landscape whatever `wm user-rotation` says, so the tall form is checked
on the SDK's `Pixel_7_Pro_API_Baklava` AVD (`emulator -avd Pixel_7_Pro_API_Baklava -no-window`,
then `adb -s emulator-5554`). One BACK on the walkthrough lands on the shell.

## Touch mode and the non-navigation buttons

Only navigation keys (the D-pad, and A once remapped to `DPAD_CENTER`) make Android leave
touch mode. L1/R1, L2/R2, B, X and Y are ordinary buttons. After any touch, or in a fresh window, a
plain `requestFocus()` on a row during an L1/R1 pane switch returns false and no cursor shows;
a `RecyclerView` takes the focus instead because it is focusable in touch mode. The one public
call that leaves touch mode is `requestFocusFromTouch()`, so every focus change a gamepad
button drives goes through `Ui.focus(view, fromKey)` with `fromKey` true, and touch never does.

