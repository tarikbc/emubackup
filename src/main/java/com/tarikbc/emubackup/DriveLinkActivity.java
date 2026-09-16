package com.tarikbc.emubackup;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Links a Google account using the device authorization grant: the app shows a short code and the
 * user enters it on any other machine.
 *
 * <p>No redirect URI, no custom scheme, no signing-certificate fingerprint, and no keyboard needed
 * on the handheld. See {@code docs/DRIVE_SETUP.md}.
 */
public class DriveLinkActivity extends Activity {

    private static final int REQ_SETUP = 1;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private DriveTokens tokens;
    private TextView code, instructions, status;
    private Button action;
    private volatile boolean stopped;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        tokens = new DriveTokens(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(color(R.color.ink_black));
        root.setPadding(dp(20), dp(28), dp(20), dp(28));
        setContentView(root);

        root.addView(heading("Google Drive"));
        instructions = body("");
        root.addView(instructions);

        code = new TextView(this);
        code.setTextColor(color(R.color.accent));
        code.setTextSize(40);
        code.setTypeface(android.graphics.Typeface.MONOSPACE);
        code.setLetterSpacing(0.15f);
        code.setPadding(0, dp(24), 0, dp(24));
        root.addView(code);

        status = body("");
        root.addView(status);

        action = new Button(this);
        action.setTextColor(color(R.color.ink_black));
        action.setBackgroundTintList(ColorStateList.valueOf(color(R.color.accent)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(24);
        root.addView(action, lp);

        if (!DriveClient.of(this).configured()) {
            // Telling someone holding a handheld to read a file in a git repository is not an
            // instruction they can follow. The setup screen is one they can.
            instructions.setText("Backups are going to a folder on this device.\n\n"
                    + "To send them to Google Drive, this install needs its own Google client. "
                    + "It is free and takes about ten minutes in a browser, once.");
            action.setText("Set up Drive");
            action.setOnClickListener(v ->
                    startActivityForResult(new Intent(this, DriveSetupActivity.class), REQ_SETUP));
            return;
        }
        if (tokens.linked()) {
            showLinked();
            return;
        }
        begin();
    }

    private void showLinked() {
        instructions.setText("Drive is linked. Backups go to a folder named EmuBackup in your "
                + "Drive, and stay visible and downloadable there.");
        code.setText("");
        String hint = tokens.store().accountHint();
        status.setText(hint == null ? "" : hint);
        status.setTextColor(color(R.color.ok));
        action.setText("Unlink");
        action.setBackgroundTintList(ColorStateList.valueOf(color(R.color.surface_high)));
        action.setTextColor(color(R.color.danger));
        action.setOnClickListener(v -> {
            tokens.store().clear();
            recreate();
        });
    }

    private void begin() {
        instructions.setText("Starting…");
        action.setText("Cancel");
        action.setOnClickListener(v -> finish());

        io.execute(() -> {
            try {
                DeviceCodeAuth.Pending p = tokens.auth().begin(System.currentTimeMillis());
                post(() -> {
                    instructions.setText("On any other device, open\n" + p.verificationUrl
                            + "\nand enter this code:");
                    code.setText(p.userCode);
                    status.setText("Waiting for you to approve…");
                    status.setTextColor(color(R.color.text_secondary));
                });
                pollUntilDone(p);
            } catch (Exception e) {
                post(() -> fail(message(e)));
            }
        });
    }

    private void pollUntilDone(DeviceCodeAuth.Pending p) {
        long interval = p.intervalSeconds * 1000L;
        while (!stopped) {
            long now = System.currentTimeMillis();
            if (now > p.expiresAtMs) {
                post(() -> fail("The code expired. Start again."));
                return;
            }
            sleep(interval);
            if (stopped) return;

            DeviceCodeAuth.PollResult r;
            try {
                r = tokens.auth().poll(p.deviceCode, System.currentTimeMillis());
            } catch (Exception e) {
                // A transient network failure during polling is normal; keep waiting rather than
                // discarding a code the user may be part-way through entering.
                continue;
            }
            switch (r.state) {
                case PENDING:
                    break;
                case SLOW_DOWN:
                    // The server asks for a slower cadence; honouring it avoids being cut off.
                    interval += 5_000L;
                    break;
                case GRANTED:
                    tokens.saveRefreshToken(r.tokens.refreshToken);
                    tokens.store().setAccountHint("Linked " + Manifest.iso8601(System.currentTimeMillis()));
                    post(this::showLinked);
                    return;
                case DENIED:
                case EXPIRED:
                case FAILED:
                default:
                    final String detail = r.detail;
                    post(() -> fail(detail == null ? "Linking failed." : detail));
                    return;
            }
        }
    }

    private void fail(String text) {
        code.setText("");
        status.setText(text);
        status.setTextColor(color(R.color.danger));
        action.setText("Try again");
        action.setOnClickListener(v -> recreate());
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        // Rebuild rather than patch: a saved client changes which of the three states this
        // screen is in, and recreate() is the only path that cannot leave a stale one behind.
        if (req == REQ_SETUP && result == RESULT_OK) recreate();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        stopped = true;
        io.shutdownNow();
    }

    private void post(Runnable r) {
        ui.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            r.run();
        });
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String message(Exception e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    private TextView heading(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(color(R.color.text_primary));
        v.setTextSize(24);
        v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        return v;
    }

    private TextView body(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(color(R.color.text_secondary));
        v.setTextSize(15);
        v.setPadding(0, dp(10), 0, 0);
        v.setLineSpacing(dp(4), 1f);
        return v;
    }

    private int dp(int v) { return (int) (getResources().getDisplayMetrics().density * v); }

    private int color(int res) { return getResources().getColor(res, null); }
}
