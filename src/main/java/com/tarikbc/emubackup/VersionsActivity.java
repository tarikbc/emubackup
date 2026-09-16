package com.tarikbc.emubackup;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The backups that exist, newest first. Tapping one opens the restore preview. */
public class VersionsActivity extends Activity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView subtitle;
    private RecyclerView list;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_list);
        ((TextView) findViewById(R.id.title)).setText(R.string.versions_title);
        subtitle = findViewById(R.id.subtitle);

        list = new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        ((LinearLayout) findViewById(R.id.container)).addView(list,
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    @Override protected void onResume() {
        super.onResume();
        io.execute(() -> {
            // Both happen off the main thread: listing a Drive store is a network round trip,
            // and even the local one touches disk.
            final List<IndexEntry> vs = RestoreSession.versions(this);
            final String where = RestoreSession.describeStore(this);
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                render(vs, where);
            });
        });
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    private void render(List<IndexEntry> versions, String where) {
        List<IndexEntry> newestFirst = new ArrayList<>(versions);
        Collections.reverse(newestFirst);

        if (newestFirst.isEmpty()) {
            // Naming the store even when it is empty is the point: this screen silently showed
            // the local folder while backups were going to Drive, and said nothing about where
            // it had looked.
            subtitle.setText(getString(R.string.no_versions) + "\n" + where);
        } else {
            long total = 0;
            for (IndexEntry e : newestFirst) total += e.bytes;
            subtitle.setText(newestFirst.size() + " backups · " + Sizes.human(total)
                    + " · " + where);
        }
        list.setAdapter(new Adapter(newestFirst));
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        private final List<IndexEntry> items;
        private final SimpleDateFormat fmt = new SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault());

        Adapter(List<IndexEntry> items) { this.items = items; }

        @Override public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.row_version, parent, false));
        }

        @Override public void onBindViewHolder(Holder h, int position) {
            IndexEntry e = items.get(position);
            h.when.setText(fmt.format(new Date(e.createdAtMs)));
            h.size.setText(Sizes.human(e.bytes));

            StringBuilder sub = new StringBuilder(e.id);
            if (e.isPreRestore()) {
                sub.append("  ·  saved automatically before a restore");
                h.when.setTextColor(getResources().getColor(R.color.warn, null));
            } else {
                h.when.setTextColor(getResources().getColor(R.color.text_primary, null));
            }
            if (e.pinned) sub.append("  ·  pinned");
            h.sub.setText(sub.toString());

            h.itemView.setOnClickListener(v -> {
                Intent i = new Intent(VersionsActivity.this, RestorePreviewActivity.class);
                i.putExtra(RestorePreviewActivity.EXTRA_VERSION, e.id);
                startActivity(i);
            });
        }

        @Override public int getItemCount() { return items.size(); }
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView when, size, sub;
        Holder(View v) {
            super(v);
            when = v.findViewById(R.id.when);
            size = v.findViewById(R.id.size);
            sub = v.findViewById(R.id.sub);
        }
    }
}
