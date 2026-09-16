package com.tarikbc.emubackup;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * What every run did, kept so that a schedule cannot fail quietly.
 *
 * <p>The failure this app exists to prevent is the silent one, and a scheduler is the easiest
 * place to reintroduce it: a job that retries forever, out of sight, leaves someone believing
 * they are covered for as long as they never check. So runs are recorded whatever the outcome,
 * and {@link #consecutiveFailures()} is what the caller escalates on.
 *
 * <p>Android-free by design; see {@code test.sh}.
 */
public final class RunLog {

    /** Enough to show a pattern, small enough to keep in preferences and read at a glance. */
    public static final int MAX_ENTRIES = 40;

    /** Failures in a row before the app stops being quiet about it. */
    public static final int ESCALATE_AFTER = 3;

    public static final class Run {
        public final long atMs;
        /** {@code "manual"} or {@code "scheduled"}. */
        public final String kind;
        public final boolean ok;
        /** The version written, or null when none was. */
        public final String versionId;
        public final long bytes;
        /** One line: the summary on success, the reason on failure. */
        public final String detail;

        public Run(long atMs, String kind, boolean ok, String versionId, long bytes, String detail) {
            this.atMs = atMs;
            this.kind = kind;
            this.ok = ok;
            this.versionId = versionId;
            this.bytes = bytes;
            this.detail = detail == null ? "" : detail;
        }
    }

    private final List<Run> runs;

    public RunLog(List<Run> runs) {
        List<Run> copy = new ArrayList<>(runs);
        // Newest first, so trimming takes the oldest and reading takes the top.
        copy.sort((a, b) -> Long.compare(b.atMs, a.atMs));
        if (copy.size() > MAX_ENTRIES) copy = new ArrayList<>(copy.subList(0, MAX_ENTRIES));
        this.runs = Collections.unmodifiableList(copy);
    }

    public static RunLog empty() {
        return new RunLog(new ArrayList<>());
    }

    /** Newest first. */
    public List<Run> runs() {
        return runs;
    }

    public RunLog with(Run r) {
        List<Run> next = new ArrayList<>(runs);
        next.add(r);
        return new RunLog(next);
    }

    public Run last() {
        return runs.isEmpty() ? null : runs.get(0);
    }

    /**
     * Failures since the most recent success.
     *
     * <p>Counts scheduled runs only. A manual run is watched by the person who started it, and
     * letting a hand-triggered failure escalate the schedule would fire the alarm for something
     * they already saw.
     */
    public int consecutiveFailures() {
        int n = 0;
        for (Run r : runs) {
            if (!"scheduled".equals(r.kind)) continue;
            if (r.ok) break;
            n++;
        }
        return n;
    }

    public boolean shouldEscalate() {
        return consecutiveFailures() >= ESCALATE_AFTER;
    }

    /** The log as plain text, for the share action. Anything else needs a tool to read. */
    public String toPlainText() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        f.setTimeZone(TimeZone.getDefault());
        StringBuilder b = new StringBuilder("EmuBackup run log\n\n");
        if (runs.isEmpty()) b.append("No runs recorded yet.\n");
        for (Run r : runs) {
            b.append(f.format(new Date(r.atMs)))
             .append("  ").append(r.ok ? "ok     " : "FAILED ")
             .append(pad(r.kind, 10));
            if (r.versionId != null) b.append(r.versionId).append("  ").append(Sizes.human(r.bytes));
            b.append('\n');
            if (!r.detail.isEmpty()) {
                for (String line : r.detail.split("\n")) b.append("        ").append(line).append('\n');
            }
        }
        return b.toString();
    }

    private static String pad(String s, int width) {
        StringBuilder b = new StringBuilder(s == null ? "" : s);
        while (b.length() < width) b.append(' ');
        return b.toString();
    }

    /*
     * Android's org.json declares checked JSONException on put(); libs/json.jar does not. See the
     * same note in BackupIndex. These objects are built here from this app's own data, so a
     * failure is a programming error and is rethrown unchecked.
     */
    public String toJson() {
        try {
            JSONArray a = new JSONArray();
            for (Run r : runs) {
                JSONObject j = new JSONObject();
                j.put("atMs", r.atMs);
                j.put("kind", r.kind);
                j.put("ok", r.ok);
                if (r.versionId != null) j.put("versionId", r.versionId);
                j.put("bytes", r.bytes);
                j.put("detail", r.detail);
                a.put(j);
            }
            JSONObject o = new JSONObject();
            o.put("runs", a);
            return o.toString();
        } catch (JSONException e) {
            throw new IllegalStateException("malformed JSON in RunLog", e);
        }
    }

    public static RunLog fromJson(String json) {
        if (json == null || json.isEmpty()) return empty();
        try {
            JSONArray a = new JSONObject(json).optJSONArray("runs");
            List<Run> out = new ArrayList<>();
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject j = a.getJSONObject(i);
                String v = j.has("versionId") ? j.getString("versionId") : null;
                out.add(new Run(j.optLong("atMs"), j.optString("kind", "manual"),
                        j.optBoolean("ok", false), v, j.optLong("bytes"),
                        j.optString("detail", "")));
            }
            return new RunLog(out);
        } catch (JSONException e) {
            // A corrupt log is not worth failing a backup over, and losing history is survivable.
            return empty();
        }
    }
}
