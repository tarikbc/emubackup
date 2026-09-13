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
        sb.append("inventory target ").append(Sizes.human(765460480L)).append(" of saves on the reference device\n");

        ((TextView) findViewById(R.id.status)).setText(sb.toString());
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Loads and validates the bundled registry. Reported here rather than assumed, because a
     * registry that fails to parse means the app would scan nothing at all — the one failure
     * that must never be silent.
     */
    private String registryStatus() {
        try {
            TargetRegistry reg = TargetRegistry.parse(Assets.readString(this, "targets.json"));
            int shared = reg.targetsOfTier(Tier.SHARED).size();
            int priv = reg.targetsOfTier(Tier.APP_PRIVATE).size();
            return reg.emulators().size() + " emulators, " + reg.allTargets().size() + " targets\n"
                    + "                 " + shared + " shared, " + priv + " need Shizuku\n"
                    + "                 " + reg.defaultEnabledTargets().size() + " on by default"
                    + (reg.warnings().isEmpty() ? "" : "\n                 " + reg.warnings().size() + " warning(s)");
        } catch (Exception e) {
            return "FAILED: " + e.getMessage();
        }
    }
}
