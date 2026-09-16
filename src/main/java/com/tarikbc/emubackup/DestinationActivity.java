package com.tarikbc.emubackup;

import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Where backups go: Google Drive, a folder you pick, or this device. Strongest first.
 *
 * <p>Three rows, each saying what it protects against in one sentence, with the one in use
 * marked and one button per row. Changing this never moves or deletes anything already
 * backed up; each place keeps its own versions.
 */
public class DestinationActivity extends GamepadActivity {

    private static final int REQ_TREE = 1;

    private FrameLayout root;
    private LinearLayout col;
    private SheetView sheet;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        root = new FrameLayout(this);
        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        // Rebuilt every time: the person may have just linked an account, picked a folder, or
        // come back from Settings having revoked the grant this screen is describing.
        render();
    }

    @Override public void onConfigurationChanged(Configuration cfg) {
        super.onConfigurationChanged(cfg);
        render();
    }

    @Override public void onBackPressed() {
        if (sheet != null && sheet.isShowing()) {
            sheet.dismiss();
            sheet = null;
            return;
        }
        super.onBackPressed();
    }

    private void render() {
        root.removeAllViews();
        boolean tall = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        ScrollView sv = new ScrollView(this);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int gx = Ui.dp(this, tall ? 20 : 32), gy = Ui.dp(this, 20);
        col.setPadding(gx, gy, gx, gy);
        sv.addView(col);
        root.addView(sv);

        Destination.Kind active = Destination.effective(this);
        col.addView(Ui.bold(this, "Where backups go", 24, R.color.text_primary));
        if (Destination.chosenButUnavailable(this)) {
            TextView warn = Ui.text(this, "The place you chose is not available right now, so "
                    + "backups go to this device until it is back. Choose it again below.", 15, R.color.warn);
            col.addView(warn, top(8));
        }
        col.addView(Ui.text(this, "Strongest first. Changing this never moves or deletes anything "
                + "already backed up.", 15, R.color.text_secondary), top(6));

        boolean driveLinked = new DriveTokens(this).linked();
        boolean first = true;
        first = place(R.drawable.ic_cloud, "Google Drive", active == Destination.Kind.DRIVE,
                "Survives losing or wiping the device. Needs your own Google client once, about "
                        + "ten minutes, and then nothing.",
                !driveLinked ? (DriveClient.of(this).configured() ? "Link an account" : "Set up Google Drive")
                        : active == Destination.Kind.DRIVE ? "Account and unlink" : "Use Google Drive",
                () -> {
                    if (!driveLinked || active == Destination.Kind.DRIVE) {
                        startActivity(new Intent(this, DriveLinkActivity.class));
                    } else {
                        Destination.chooseDrive(this);
                        render();
                    }
                }, first);

        String folderNote = active == Destination.Kind.FOLDER ? Destination.folderLabel(this) : null;
        first = place(R.drawable.ic_folder, "A folder you pick", active == Destination.Kind.FOLDER,
                (folderNote != null ? folderNote + "\n" : "") + "An SD card, a USB drive, or a folder "
                        + "a sync app watches, so backups leave the device with no account here at all.",
                active == Destination.Kind.FOLDER ? "Pick a different folder" : "Pick a folder",
                this::pickFolder, first);

        first = place(R.drawable.ic_smartphone, "This device", active == Destination.Kind.DEVICE,
                "A folder in this device's own storage. It survives an emulator wiping its saves, "
                        + "which is the common way they are lost, and nothing that happens to the device.",
                active == Destination.Kind.DEVICE ? null : "Use this device",
                () -> {
                    Destination.chooseDevice(this);
                    render();
                }, first);
    }

    /** One place: icon, name, "in use" when it is, a sentence, and its button. Returns false once a button took default focus. */
    private boolean place(int icon, String name, boolean inUse, String sentence, String button,
                          Runnable go, boolean firstButton) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.card(this, inUse ? R.color.surface_high : R.color.surface));
        int p = Ui.dp(this, 20);
        card.setPadding(p, p, p, p);

        TextView title = Ui.bold(this, name + (inUse ? "  \u00b7  in use" : ""), 18,
                inUse ? R.color.accent : R.color.text_primary);
        title.setGravity(android.view.Gravity.CENTER_VERTICAL);
        Ui.iconStart(title, icon, inUse ? R.color.accent : R.color.text_secondary, 20, 10);
        card.addView(title);
        card.addView(Ui.text(this, sentence, 15, R.color.text_secondary), top(8));
        if (button != null) {
            TextView b = inUse ? Ui.secondaryButton(this, button) : Ui.primaryButton(this, button);
            b.setOnClickListener(v -> go.run());
            if (firstButton) b.setFocusedByDefault(true);
            LinearLayout.LayoutParams lp = top(14);
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            card.addView(b, lp);
            if (firstButton) focusByDefault(b);
            firstButton = false;
        }
        col.addView(card, top(14));
        return firstButton;
    }

    private LinearLayout.LayoutParams top(int dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, dp);
        return lp;
    }

    private void pickFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_TREE);
        } catch (Exception e) {
            sheet = SheetView.show(root, "No folder picker", "This device has no way to pick a "
                    + "folder. Google Drive or this device still work.", null, "OK", null);
        }
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_TREE || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri tree = data.getData();
        try {
            Destination.chooseFolder(this, tree, readableName(tree));
        } catch (Exception e) {
            sheet = SheetView.show(root, "That folder could not be used", String.valueOf(e.getMessage()),
                    null, "OK", null);
            return;
        }
        render();
    }

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
}
