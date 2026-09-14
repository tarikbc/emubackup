package com.tarikbc.emubackup;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Every target in the registry, with what the scan found for it.
 *
 * <p>This is the screen that makes the app auditable: it answers "what will this tool read?"
 * and "is it actually finding my saves?" in one scroll, before a single byte is copied.
 */
public class TargetsActivity extends Activity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView subtitle;
    private RecyclerView list;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_list);
        ((TextView) findViewById(R.id.title)).setText(R.string.targets_title);
        subtitle = findViewById(R.id.subtitle);
        subtitle.setText(R.string.scanning);

        // Constructed in code rather than declared in XML. Inflating a RecyclerView needs its
        // recyclerViewStyle attribute resolved at inflation time, and this build vendors
        // AndroidX without merging AARs.
        list = new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        ((LinearLayout) findViewById(R.id.container)).addView(list,
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    @Override protected void onResume() {
        super.onResume();
        io.execute(() -> {
            final ScanSession s = ScanSession.run(this);
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                render(s);
            });
        });
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    private void render(ScanSession s) {
        if (!s.ok()) {
            subtitle.setText("Registry failed to load: " + s.registryError);
            return;
        }

        List<TargetRow> rows = new ArrayList<>();
        for (Emulator e : s.registry.emulators()) {
            rows.add(TargetRow.header(e.label.toUpperCase(java.util.Locale.ROOT)));
            for (Target t : e.targets) {
                rows.add(TargetRow.of(t, s.scanOf(t.id), e.label));
            }
        }
        list.setAdapter(new TargetAdapter(rows));

        int locked = s.locked();
        subtitle.setText(Sizes.human(s.coveredBytes()) + " selected · "
                + Sizes.human(s.skippedBytes()) + " opt-in"
                + (locked > 0 ? " · " + locked + " locked" : ""));
    }
}
