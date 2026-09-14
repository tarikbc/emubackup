package com.tarikbc.emubackup;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

/**
 * Renders the registry with its scan results.
 *
 * <p>A locked row keeps its label, path and status visible and is only dimmed. That is
 * deliberate: the user has to be able to see what they are missing before deciding whether
 * Shizuku is worth setting up. Hiding it would make the upsell dishonest.
 *
 * <p>See {@code DESIGN.md} section 6.
 */
public final class TargetAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_TARGET = 1;

    private final List<TargetRow> rows;

    public TargetAdapter(List<TargetRow> rows) {
        this.rows = rows;
    }

    @Override public int getItemViewType(int position) {
        return rows.get(position).header ? TYPE_HEADER : TYPE_TARGET;
    }

    @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        return viewType == TYPE_HEADER
                ? new HeaderHolder(inf.inflate(R.layout.row_header, parent, false))
                : new TargetHolder(inf.inflate(R.layout.row_target, parent, false));
    }

    @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
        TargetRow row = rows.get(position);
        if (row.header) {
            ((HeaderHolder) holder).header.setText(row.headerText);
        } else {
            ((TargetHolder) holder).bind(row);
        }
    }

    @Override public int getItemCount() {
        return rows.size();
    }

    static final class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView header;
        HeaderHolder(View v) { super(v); header = v.findViewById(R.id.header); }
    }

    static final class TargetHolder extends RecyclerView.ViewHolder {
        final TextView label, chip, size, detail;

        TargetHolder(View v) {
            super(v);
            label = v.findViewById(R.id.label);
            chip = v.findViewById(R.id.chip);
            size = v.findViewById(R.id.size);
            detail = v.findViewById(R.id.detail);
        }

        void bind(TargetRow row) {
            Target t = row.target;
            TargetScan s = row.scan;
            android.content.res.Resources res = itemView.getResources();

            label.setText(t.label);
            chip.setText(chipText(t));
            chip.setTextColor(res.getColor(
                    t.category == Category.KEY ? R.color.warn : R.color.text_tertiary, null));

            // Dim a locked row rather than hiding it, so what Shizuku would unlock stays visible.
            itemView.setAlpha(s != null && s.status == TargetStatus.TIER_UNAVAILABLE ? 0.45f : 1f);

            if (s == null) {
                size.setText("");
                detail.setText(t.root);
                return;
            }

            switch (s.status) {
                case OK:
                    size.setText(Sizes.human(s.totalBytes));
                    size.setTextColor(res.getColor(R.color.text_primary, null));
                    detail.setText(s.fileCount() + " files"
                            + (s.unmatchedCount > 0 ? " · " + s.unmatchedCount + " other files ignored" : ""));
                    break;
                case EMPTY:
                    size.setText("—");
                    size.setTextColor(res.getColor(R.color.text_tertiary, null));
                    detail.setText("no saves yet");
                    break;
                case ROOT_MISSING:
                    size.setText("—");
                    size.setTextColor(res.getColor(R.color.text_tertiary, null));
                    detail.setText("folder not created yet");
                    break;
                case PKG_NOT_INSTALLED:
                    size.setText("—");
                    size.setTextColor(res.getColor(R.color.text_tertiary, null));
                    detail.setText("emulator not installed");
                    break;
                case TIER_UNAVAILABLE:
                    size.setText("locked");
                    size.setTextColor(res.getColor(R.color.text_tertiary, null));
                    detail.setText("needs Shizuku · " + shortPath(s));
                    break;
                case OVER_CAP:
                case UNREADABLE:
                    size.setText("!");
                    size.setTextColor(res.getColor(R.color.danger, null));
                    detail.setText(s.detail == null ? s.status.name() : s.detail);
                    break;
            }
        }

        /**
         * The resolved path with the external-storage prefix dropped. The full path is 60-odd
         * characters of which the first 20 are identical on every row, so trimming it is what
         * makes the part that differs actually readable.
         */
        private static String shortPath(TargetScan s) {
            if (s.resolvedRoot == null) return "";
            String p = s.resolvedRoot;
            int i = p.indexOf("/Android/data/");
            if (i >= 0) return p.substring(i + 1);
            i = p.indexOf("/emulated/0/");
            if (i >= 0) return p.substring(i + "/emulated/0/".length());
            return p;
        }

        private static String chipText(Target t) {
            String c = t.category.name();
            return t.enabledByDefault ? c : c + " · opt-in";
        }
    }
}
