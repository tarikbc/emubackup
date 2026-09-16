package com.tarikbc.emubackup;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Where backups go. Three cards, strongest first, each honest about what it protects against.
 *
 * <p>Ordering by strength rather than by convenience is the point of the screen. A backup on the
 * same device as the saves protects against an emulator update and nothing else, which is a real
 * risk and the one this app was written for, but it is not what "backed up" usually means to the
 * person reading it. Saying so on the card is cheaper than explaining it afterwards.
 */
public class DestinationActivity extends Activity {

    private static final int REQ_TREE = 1;

    private LinearLayout root;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(color(R.color.ink_black));
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(28));
        sv.addView(root);
        setContentView(sv);
    }

    @Override protected void onResume() {
        super.onResume();
        // Rebuilt every time: the person may have just linked an account, picked a folder, or
        // come back from Settings having revoked the grant this screen is describing.
        root.removeAllViews();
        Destination.Kind active = Destination.effective(this);

        root.addView(heading("Where backups go"));
        if (Destination.chosenButUnavailable(this)) {
            TextView warn = body("The destination you chose is not available right now, so "
                    + "backups are going to this device instead. Choose it again below.");
            warn.setTextColor(color(R.color.warn));
            root.addView(warn);
        }

        // --- Drive ---
        boolean driveLinked = new DriveTokens(this).linked();
        LinearLayout drive = card(active == Destination.Kind.DRIVE);
        drive.addView(cardTitle("Google Drive"
                + (active == Destination.Kind.DRIVE ? "  •  in use" : "")));
        drive.addView(body("Survives losing the device. Needs your own Google client, about ten "
                + "minutes once, and then nothing."));
        if (!driveLinked) {
            action(drive, DriveClient.of(this).configured() ? "Link an account" : "Set up Drive",
                    () -> startActivity(new Intent(this, DriveLinkActivity.class)));
        } else if (active != Destination.Kind.DRIVE) {
            action(drive, "Use Drive", () -> {
                Destination.chooseDrive(this);
                recreate();
            });
        } else {
            action(drive, "Account and unlink", () ->
                    startActivity(new Intent(this, DriveLinkActivity.class)));
        }

        // --- Picked folder ---
        LinearLayout folder = card(active == Destination.Kind.FOLDER);
        folder.addView(cardTitle("A folder you pick"
                + (active == Destination.Kind.FOLDER ? "  •  in use" : "")));
        folder.addView(body("An SD card, a USB drive, or a folder belonging to a sync app such as "
                + "Dropbox or Nextcloud, in which case backups leave the device with no account "
                + "here at all. No setup beyond picking it."));
        if (active == Destination.Kind.FOLDER) {
            TextView where = body(Destination.folderLabel(this));
            where.setTextColor(color(R.color.ok));
            folder.addView(where);
        }
        action(folder, active == Destination.Kind.FOLDER ? "Pick a different folder"
                                                         : "Pick a folder", this::pickFolder);

        // --- This device ---
        LinearLayout device = card(active == Destination.Kind.DEVICE);
        device.addView(cardTitle("This device"
                + (active == Destination.Kind.DEVICE ? "  •  in use" : "")));
        device.addView(body("A folder in internal storage. It survives an emulator wiping its own "
                + "data, which is the common way saves are lost, and nothing else. If the device "
                + "is lost or reset, the backup goes with it."));
        TextView path = body(Stores.localRoot().getAbsolutePath());
        path.setTypeface(Typeface.MONOSPACE);
        path.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        device.addView(path);
        if (active != Destination.Kind.DEVICE) {
            action(device, "Use this device", () -> {
                Destination.chooseDevice(this);
                recreate();
            });
        }

        root.addView(body("Changing this does not move or delete anything already backed up. "
                + "Each destination keeps its own versions."));
    }

    private void pickFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_TREE);
        } catch (Exception e) {
            Toast.makeText(this, "This device has no folder picker.", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_TREE || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri tree = data.getData();
        try {
            Destination.chooseFolder(this, tree, readableName(tree));
        } catch (Exception e) {
            Toast.makeText(this, "That folder could not be used: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            return;
        }
        recreate();
    }

    /**
     * A name worth showing for a picked tree.
     *
     * <p>The raw document id reads like {@code primary:Backups/EmuBackup}, which is accurate and
     * unhelpful. The provider's own display name is what the person saw in the picker.
     */
    private String readableName(Uri tree) {
        String docId = DocumentsContract.getTreeDocumentId(tree);
        Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
        try (Cursor c = getContentResolver().query(doc,
                new String[] { DocumentsContract.Document.COLUMN_DISPLAY_NAME },
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String name = c.getString(0);
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (Exception ignored) {
            // Providers are allowed to be sparse. The document id is a poor label but a real one.
        }
        return docId;
    }

    // ---- view helpers, matching PermissionActivity's programmatic style ----

    /** Adds a card to the screen and returns the column its content goes into. */
    private LinearLayout card(boolean active) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundColor(color(R.color.surface));
        c.setPadding(dp(16), dp(14), dp(16), dp(16));
        View hue = new View(this);
        hue.setBackgroundColor(color(active ? R.color.accent : R.color.text_tertiary));

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.addView(hue, new LinearLayout.LayoutParams(dp(4),
                ViewGroup.LayoutParams.MATCH_PARENT));
        wrap.addView(c, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(18);
        root.addView(wrap, lp);
        return c;
    }

    private void action(LinearLayout card, String label, Runnable onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(color(R.color.text_primary));
        b.setBackgroundTintList(ColorStateList.valueOf(color(R.color.surface_high)));
        b.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        card.addView(b, lp);
    }

    private TextView heading(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color(R.color.text_primary));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView cardTitle(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color(R.color.text_primary));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView body(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color(R.color.text_secondary));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setLineSpacing(dp(4), 1f);
        t.setPadding(0, dp(8), 0, 0);
        return t;
    }

    private int color(int res) {
        return getResources().getColor(res, null);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
