package com.tarikbc.emubackup;

import android.app.Activity;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One target broken down by game, and where the layout has a profile axis, by profile.
 *
 * <p>This is the screen that turns {@code 0100152000022000} under
 * {@code F255133E7DABC494CD4B3089D53DB2DB} into "Mario Kart 8 Deluxe" under a profile name. The
 * raw identifier stays visible underneath, in a monospace face a step down, because it is what
 * actually finds the save and hiding it would make a wrong name impossible to notice.
 */
public class GroupsActivity extends Activity {

    public static final String EXTRA_TARGET = "target";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private String targetId;
    private TextView subtitle;
    private RecyclerView list;

    private List<SaveGroup> groups = new ArrayList<>();
    private GameNames names = GameNames.empty();
    private ProfileAliases aliases;
    private boolean byProfile;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        targetId = getIntent().getStringExtra(EXTRA_TARGET);
        aliases = new ProfileAliases(this);

        setContentView(R.layout.activity_list);
        subtitle = findViewById(R.id.subtitle);
        subtitle.setText(R.string.scanning);

        list = new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        ((LinearLayout) findViewById(R.id.container)).addView(list,
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        io.execute(this::load);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    private void load() {
        String title = targetId;
        List<SaveGroup> built = new ArrayList<>();
        GameNames resolved = GameNames.empty();
        String error = null;

        try {
            TargetRegistry reg = TargetRegistry.parse(Assets.readString(this, "targets.json"));
            Target t = reg.target(targetId);
            Emulator e = reg.emulatorOf(targetId);
            title = t.label;

            Capabilities caps = new Capabilities(Permissions.hasAllFiles(), false, false);
            PathResolver res = new PathResolver(
                    Environment.getExternalStorageDirectory().getAbsolutePath());
            LocalFileSource src = new LocalFileSource();
            ScanEngine scanner = new ScanEngine(res, src, null, caps, new AppInfo(this));

            TargetScan scan = scanner.scan(e, t);
            built = GroupBuilder.build(targetId, t.grouping, scan.files);
            resolved = RomIndexer.build(this).names;
        } catch (Exception ex) {
            error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        }

        final String fTitle = title, fError = error;
        final List<SaveGroup> fGroups = built;
        final GameNames fNames = resolved;
        ui.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            ((TextView) findViewById(R.id.title)).setText(fTitle);
            if (fError != null) {
                subtitle.setText(fError);
                return;
            }
            groups = fGroups;
            names = fNames;
            byProfile = hasProfileAxis();
            render();
        });
    }

    private boolean hasProfileAxis() {
        for (SaveGroup g : groups) if (g.profileKey != null) return true;
        return false;
    }

    private void render() {
        long bytes = 0;
        for (SaveGroup g : groups) bytes += g.bytes;
        StringBuilder s = new StringBuilder();
        s.append(groups.size()).append(groups.size() == 1 ? " group · " : " groups · ")
                .append(Sizes.human(bytes));
        if (hasProfileAxis()) s.append("  ·  tap the header to switch axis");
        subtitle.setText(s.toString());
        subtitle.setOnClickListener(hasProfileAxis() ? v -> {
            byProfile = !byProfile;
            render();
        } : null);
        list.setAdapter(new Adapter(rows()));
    }

    /** Header rows plus group rows, ordered by whichever axis is selected. */
    private List<Object> rows() {
        List<Object> out = new ArrayList<>();
        if (!hasProfileAxis()) {
            for (SaveGroup g : groups) out.add(g);
            return out;
        }
        Map<String, List<SaveGroup>> buckets = new LinkedHashMap<>();
        for (SaveGroup g : groups) {
            String k = byProfile ? String.valueOf(g.profileKey) : displayName(g);
            buckets.computeIfAbsent(k, x -> new ArrayList<>()).add(g);
        }
        for (Map.Entry<String, List<SaveGroup>> e : buckets.entrySet()) {
            out.add(byProfile ? aliases.nameFor(e.getKey()).toUpperCase(java.util.Locale.ROOT)
                              : e.getKey().toUpperCase(java.util.Locale.ROOT));
            out.addAll(e.getValue());
        }
        return out;
    }

    private String displayName(SaveGroup g) {
        if (g.isWholeTarget()) return "Whole target";
        if (g.isUngrouped()) return "Other files";
        return names.lookup(g.gameIdKind, g.gameKey);
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final List<Object> items;

        Adapter(List<Object> items) { this.items = items; }

        @Override public int getItemViewType(int position) {
            return items.get(position) instanceof String ? 0 : 1;
        }

        @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            return type == 0
                    ? new TargetAdapter.HeaderHolder(inf.inflate(R.layout.row_header, parent, false))
                    : new Holder(inf.inflate(R.layout.row_group, parent, false));
        }

        @Override public void onBindViewHolder(RecyclerView.ViewHolder h, int position) {
            Object item = items.get(position);
            if (item instanceof String) {
                ((TargetAdapter.HeaderHolder) h).header.setText((String) item);
                return;
            }
            SaveGroup g = (SaveGroup) item;
            Holder v = (Holder) h;

            v.name.setText(displayName(g));
            v.size.setText(Sizes.human(g.bytes));

            StringBuilder sub = new StringBuilder();
            if (!g.isWholeTarget() && !g.isUngrouped()) {
                sub.append(g.gameKey);
                // A name we could not resolve is marked, so a raw id is never mistaken for a title.
                if (!names.isKnown(g.gameIdKind, g.gameKey)) sub.append("  (not in your library)");
                sub.append("  ·  ");
            }
            sub.append(g.files.size()).append(g.files.size() == 1 ? " file" : " files");
            if (g.newestMtimeMs > 0) sub.append("  ·  ").append(age(g.newestMtimeMs));
            if (!byProfile && g.profileKey != null) {
                sub.append("  ·  ").append(aliases.nameFor(g.profileKey));
            }
            v.sub.setText(sub.toString());
        }

        @Override public int getItemCount() { return items.size(); }
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView name, size, sub;
        Holder(View v) {
            super(v);
            name = v.findViewById(R.id.name);
            size = v.findViewById(R.id.size);
            sub = v.findViewById(R.id.sub);
        }
    }

    private static String age(long mtimeMs) {
        long days = (System.currentTimeMillis() - mtimeMs) / 86_400_000L;
        if (days <= 0) return "today";
        if (days == 1) return "yesterday";
        if (days < 30) return days + " days ago";
        if (days < 365) return (days / 30) + " months ago";
        return (days / 365) + " years ago";
    }
}
