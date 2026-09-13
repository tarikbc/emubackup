package com.tarikbc.emubackup;

import android.app.Activity;
import android.os.Bundle;
import android.os.Environment;
import android.widget.TextView;

/**
 * The hub screen.
 *
 * <p>Scaffolding only at this stage. It exists to prove the Gradle-free pipeline end to
 * end — resources, assets, AIDL, dexing, signing — before any scanning logic lands. The
 * status block therefore reports what the build can already establish: whether all-files
 * access is held, and whether the bundled registry asset is readable.
 *
 * <p>See {@code docs/ARCHITECTURE.md} for the screen graph this grows into.
 */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        StringBuilder sb = new StringBuilder();
        sb.append("version          ").append(versionName()).append('\n');
        sb.append("android sdk      ").append(android.os.Build.VERSION.SDK_INT).append('\n');
        sb.append("all-files access ").append(Environment.isExternalStorageManager() ? "granted" : "NOT granted").append('\n');
        sb.append("drive client     ").append(OAuthConfig.isConfigured() ? "configured" : "not configured (local only)").append('\n');
        sb.append("registry asset   ").append(registryStatus()).append('\n');
        sb.append("ext root         ").append(Environment.getExternalStorageDirectory()).append('\n');
        sb.append('\n');
        sb.append("inventory target ").append(Sizes.human(765460480L)).append(" of saves\n");

        ((TextView) findViewById(R.id.status)).setText(sb.toString());
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** Confirms {@code -A src/main/assets} actually shipped the registry into the APK. */
    private String registryStatus() {
        try {
            byte[] b = Assets.readBytes(this, "targets.json");
            return "ok (" + Sizes.human(b.length) + ")";
        } catch (Exception e) {
            return "UNREADABLE: " + e.getClass().getSimpleName();
        }
    }
}
