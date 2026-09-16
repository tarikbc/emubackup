package com.tarikbc.emubackup;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/**
 * Every screen extends this. It makes a gamepad mean something, and stays out of the way when
 * there is none.
 *
 * <p>Measured on the Thor with a probe screen (docs/INPUT.md has the log):
 * <ul>
 *   <li>The face buttons arrive raw: {@code BUTTON_A}, {@code BUTTON_B}, X, Y, L1, R1, L2, R2.
 *       Android's focus system activates only on {@code DPAD_CENTER}/{@code ENTER} and goes
 *       back only on {@code BACK}, so without this class A and B do nothing at all.</li>
 *   <li>The D-pad is a hat axis and the left stick is an axis pair, and the framework's own
 *       synthetic joystick handler already turns <em>both</em> into {@code DPAD_*} keys, which
 *       arrive here with {@code FLAG_FALLBACK}. The app must not convert them again or focus
 *       moves twice per nudge. Those events are passed through untouched.</li>
 * </ul>
 *
 * <p>So: A is re-dispatched as {@code DPAD_CENTER}, which keeps the framework's pressed visuals
 * and works for Buttons and RecyclerView rows alike. B calls {@link #onBackPressed()} on release.
 * X, Y, L1 and R1 are hooks a screen may override, with one rule: <b>anything a hook does must
 * also be reachable by touch</b>. A gamepad is an accelerator, never the only way in.
 *
 * <p>It also owns the {@link LegendBar} along the bottom. The bar is shown only while a gamepad
 * is present, so someone on a phone never sees A/B chips they cannot press.
 */
public abstract class GamepadActivity extends Activity {

    private static final String TAG = "EmuPad";

    private FrameLayout content;
    private LegendBar legend;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // ---- layout: content above a legend bar ----

    @Override public void setContentView(int layoutId) {
        setContentView(LayoutInflater.from(this).inflate(layoutId, null));
    }

    @Override public void setContentView(View view) {
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(getResources().getColor(R.color.ink_black, null));

        content = new FrameLayout(this);
        shell.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        legend = new LegendBar(this);
        shell.addView(legend, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));

        content.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        super.setContentView(shell);
        setLegend("A", "Select", "B", "Back");
    }

    /** Pairs: button, meaning. Call whenever the meaning changes on this screen. */
    protected void setLegend(String... pairs) {
        if (legend != null) legend.set(pairs);
    }

    /** Puts focus somewhere sensible once the screen is laid out. */
    protected void focusByDefault(View v) {
        if (v == null) return;
        v.post(() -> {
            if (!isFinishing() && !isDestroyed()) v.requestFocus();
        });
    }

    // ---- hooks ----

    protected void onGamepadX() { }
    protected void onGamepadY() { }
    protected void onGamepadL1() { }
    protected void onGamepadR1() { }

    /** Debug hook; the probe screen overrides it to show what arrived. */
    protected void onInputLogged(String line) { }

    // ---- keys ----

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        boolean fallback = (e.getFlags() & KeyEvent.FLAG_FALLBACK) != 0;
        log("key " + KeyEvent.keyCodeToString(e.getKeyCode())
                + (e.getAction() == KeyEvent.ACTION_DOWN ? " down" : " up")
                + (fallback ? " FALLBACK" : "")
                + " src=0x" + Integer.toHexString(e.getSource())
                + " rep=" + e.getRepeatCount());

        if (fallback) return super.dispatchKeyEvent(e);

        boolean up = e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled();
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_BUTTON_A:
                return super.dispatchKeyEvent(remap(e, KeyEvent.KEYCODE_DPAD_CENTER));
            case KeyEvent.KEYCODE_BUTTON_B:
                if (up) onBackPressed();
                return true;
            case KeyEvent.KEYCODE_BUTTON_X:
                if (up) onGamepadX();
                return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
                if (up) onGamepadY();
                return true;
            case KeyEvent.KEYCODE_BUTTON_L1:
                if (up) onGamepadL1();
                return true;
            case KeyEvent.KEYCODE_BUTTON_R1:
                if (up) onGamepadR1();
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private static KeyEvent remap(KeyEvent e, int keyCode) {
        return new KeyEvent(e.getDownTime(), e.getEventTime(), e.getAction(), keyCode,
                e.getRepeatCount(), e.getMetaState(), e.getDeviceId(), e.getScanCode(),
                e.getFlags(), e.getSource());
    }

    // ---- gamepad presence, for the legend ----

    private final android.hardware.input.InputManager.InputDeviceListener devices =
            new android.hardware.input.InputManager.InputDeviceListener() {
                @Override public void onInputDeviceAdded(int id) { refreshLegend(); }
                @Override public void onInputDeviceRemoved(int id) { refreshLegend(); }
                @Override public void onInputDeviceChanged(int id) { refreshLegend(); }
            };

    @Override protected void onResume() {
        super.onResume();
        ((android.hardware.input.InputManager) getSystemService(INPUT_SERVICE))
                .registerInputDeviceListener(devices, ui);
        refreshLegend();
    }

    @Override protected void onPause() {
        super.onPause();
        ((android.hardware.input.InputManager) getSystemService(INPUT_SERVICE))
                .unregisterInputDeviceListener(devices);
    }

    private void refreshLegend() {
        if (legend != null) legend.setVisibility(gamepadPresent() ? View.VISIBLE : View.GONE);
    }

    /** True when a physical game controller is attached; the Thor's built-in one always is. */
    public static boolean gamepadPresent() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null || d.isVirtual()) continue;
            int s = d.getSources();
            if ((s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) return true;
        }
        return false;
    }

    private void log(String line) {
        Log.d(TAG, line);
        onInputLogged(line);
    }

    protected int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
