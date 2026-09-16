package com.tarikbc.emubackup;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Where a person gives this install its own Google OAuth client.
 *
 * <p>The console work cannot be done from here, and pretending otherwise would waste the ten
 * minutes it takes. What this screen can do is make the work followable from a handheld: the
 * steps in the order the console actually presents them, the scope string copyable rather than
 * typed on a gamepad, and the whole checklist copyable so it can be pasted on the machine where
 * the browser is.
 *
 * <p>The steps are duplicated in {@code docs/DRIVE_SETUP.md}. That is deliberate: the document
 * is for someone reading the repository, and this screen is for someone holding the device with
 * no repository in front of them.
 */
public class DriveSetupActivity extends Activity {

    public static final String SCOPE = "https://www.googleapis.com/auth/drive.file";

    private static final String[] STEPS = {
        "Create a project at console.cloud.google.com.",
        "Enable the Google Drive API.",
        "Google Auth Platform → Overview. Run the setup wizard. Audience: External.",
        "Clients → Create client → TVs and Limited Input devices. Copy the ID and the "
            + "secret from the dialog. The secret is shown once.",
        "Data access → Add or remove scopes. Paste the scope below under \"Add scopes "
            + "manually\", add it, and save. Add no other scope.",
        "Branding. Fill in a home page, a privacy policy and a terms link, then add that "
            + "domain under Authorized domains. Save. Publishing stays greyed out until this "
            + "page is complete.",
        "Audience → Publish app → Confirm. It must read \"In production\". In testing, "
            + "Google drops the authorization after seven days and backups fail weekly.",
    };

    private EditText idField, secretField;
    private TextView error;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(color(R.color.ink_black));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(28));
        sv.addView(root);
        setContentView(sv);

        root.addView(heading("Set up Google Drive"));
        root.addView(body("EmuBackup has no Drive account of its own, and a released build carries "
                + "no Google credentials. An app cannot keep a secret once it is installed, so one "
                + "shared client would put every user on one project and one quota.\n\n"
                + "So this install uses a client you create. It is free, it takes about ten "
                + "minutes once, and only your device ever uses it."));

        root.addView(subheading("In a browser"));
        for (int i = 0; i < STEPS.length; i++) {
            root.addView(step(i + 1, STEPS[i]));
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(secondary("Open console", () ->
                openUrl("https://console.cloud.google.com/")), rowItem());
        row.addView(secondary("Copy the scope", () ->
                copy("Drive scope", SCOPE)), rowItem());
        root.addView(row, marginTop(dp(16)));
        root.addView(secondary("Copy all steps", this::copySteps), marginTop(dp(10)));

        root.addView(subheading("Then paste them here"));
        idField = field("Client ID, or the whole downloaded JSON");
        root.addView(idField);
        secretField = field("Client secret");
        root.addView(secretField, marginTop(dp(10)));

        error = body("");
        error.setTextColor(color(R.color.danger));
        root.addView(error);

        Button save = new Button(this);
        save.setText("Save");
        save.setTextColor(color(R.color.ink_black));
        save.setBackgroundTintList(ColorStateList.valueOf(color(R.color.accent)));
        save.setOnClickListener(v -> onSave());
        root.addView(save, marginTop(dp(14)));

        if (DriveClient.isUserSupplied(this)) {
            DriveClient current = DriveClient.of(this);
            idField.setText(current.id);
            secretField.setText(current.secret);
            Button forget = new Button(this);
            forget.setText("Remove this client");
            forget.setTextColor(color(R.color.danger));
            forget.setBackgroundTintList(ColorStateList.valueOf(color(R.color.surface_high)));
            forget.setOnClickListener(v -> {
                DriveClient.clear(this);
                toast("Client removed. Backups go to a local folder.");
                finish();
            });
            root.addView(forget, marginTop(dp(10)));
        }
    }

    private void onSave() {
        OAuthClientInput in = OAuthClientInput.parse(
                idField.getText().toString(), secretField.getText().toString());
        if (!in.ok()) {
            error.setText(in.error);
            return;
        }
        // Saving drops any existing authorization, because a refresh token belongs to the client
        // that obtained it. The next screen asks the person to link, which is the honest outcome.
        DriveClient.save(this, in.id, in.secret);
        toast("Client saved. Link your account next.");
        setResult(RESULT_OK);
        finish();
    }

    private void copySteps() {
        StringBuilder b = new StringBuilder("EmuBackup — Google Drive setup\n\n");
        for (int i = 0; i < STEPS.length; i++) {
            b.append(i + 1).append(". ").append(STEPS[i]).append('\n');
        }
        b.append("\nScope: ").append(SCOPE).append('\n');
        copy("EmuBackup Drive setup", b.toString());
    }

    private void copy(String label, String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText(label, text));
        toast("Copied");
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
            // No browser on the device. The steps are on screen and copyable, which is the whole
            // point of this screen working without one.
            toast("No browser on this device. Copy the steps instead.");
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---- small view helpers, matching PermissionActivity's programmatic style ----

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(false);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setTextColor(color(R.color.text_primary));
        e.setHintTextColor(color(R.color.text_tertiary));
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        e.setTypeface(Typeface.MONOSPACE);
        e.setBackgroundTintList(ColorStateList.valueOf(color(R.color.text_tertiary)));
        return e;
    }

    private TextView heading(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color(R.color.text_primary));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView subheading(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color(R.color.text_primary));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(24), 0, dp(8));
        return t;
    }

    private TextView body(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color(R.color.text_secondary));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setLineSpacing(dp(4), 1f);
        t.setPadding(0, dp(10), 0, 0);
        return t;
    }

    private View step(int n, String s) {
        TextView t = body(n + ".  " + s);
        t.setPadding(0, dp(8), 0, 0);
        return t;
    }

    private Button secondary(String label, Runnable onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(color(R.color.text_primary));
        b.setBackgroundTintList(ColorStateList.valueOf(color(R.color.surface_high)));
        b.setOnClickListener(v -> onClick.run());
        return b;
    }

    private LinearLayout.LayoutParams rowItem() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(8);
        return lp;
    }

    private LinearLayout.LayoutParams marginTop(int px) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px;
        return lp;
    }

    private int color(int res) {
        return getResources().getColor(res, null);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
