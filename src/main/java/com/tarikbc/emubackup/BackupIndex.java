package com.tarikbc.emubackup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The list of versions in a backup store.
 *
 * <p>A convenience, never a source of truth: {@link #rebuildFrom} reconstructs it by reading the
 * manifests, so losing or corrupting {@code index.json} costs a slow startup and nothing else.
 * Anything that must survive lives in the version directories themselves.
 *
 * <p>See {@code FORMAT.md} section 8.
 */
public final class BackupIndex {

    public static final int INDEX_VERSION = 1;
    public static final String FILE_NAME = "index.json";

    private final List<IndexEntry> versions;

    public BackupIndex(List<IndexEntry> versions) {
        List<IndexEntry> copy = new ArrayList<>(versions);
        copy.sort(Comparator.comparingInt(e -> VersionId.parse(e.id).counter));
        this.versions = Collections.unmodifiableList(copy);
    }

    public static BackupIndex empty() {
        return new BackupIndex(new ArrayList<>());
    }

    public List<IndexEntry> versions() {
        return versions;
    }

    public IndexEntry newest() {
        return versions.isEmpty() ? null : versions.get(versions.size() - 1);
    }

    public int highestCounter() {
        int n = 0;
        for (IndexEntry e : versions) n = Math.max(n, VersionId.parse(e.id).counter);
        return n;
    }

    public BackupIndex with(IndexEntry e) {
        List<IndexEntry> out = new ArrayList<>(versions);
        out.removeIf(x -> x.id.equals(e.id));
        out.add(e);
        return new BackupIndex(out);
    }

    /*
     * Android's org.json declares checked JSONException on put() and the getters, while the
     * reference org.json in libs/json.jar does not. Anything shared between the JVM tests and the
     * APK therefore has to handle it, or the tests pass while the build fails — the same trap as
     * JSONObject.keySet(). These objects are built by this app from its own data, so a failure
     * here is a programming error, not a data error, and is rethrown unchecked.
     */
    public String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("indexVersion", INDEX_VERSION);
            JSONArray a = new JSONArray();
            for (IndexEntry e : versions) {
                JSONObject j = new JSONObject();
                j.put("id", e.id);
                j.put("createdAtMs", e.createdAtMs);
                j.put("bytes", e.bytes);
                j.put("pinned", e.pinned);
                j.put("kind", e.kind);
                a.put(j);
            }
            o.put("versions", a);
            return o.toString(2);
            } catch (org.json.JSONException e) {
            throw new IllegalStateException("malformed JSON in BackupIndex", e);
        }
    }

    public static BackupIndex fromJson(String json) {
        try {
            JSONObject o = new JSONObject(json);
            int v = o.optInt("indexVersion", 1);
            if (v > INDEX_VERSION) throw new IllegalArgumentException("indexVersion " + v + " is too new");
            List<IndexEntry> out = new ArrayList<>();
            JSONArray a = o.optJSONArray("versions");
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject j = a.getJSONObject(i);
                out.add(new IndexEntry(j.getString("id"), j.optLong("createdAtMs"), j.optLong("bytes"),
                        j.optBoolean("pinned", false), j.optString("kind", "manual")));
            }
            return new BackupIndex(out);
            } catch (org.json.JSONException e) {
            throw new IllegalStateException("malformed JSON in BackupIndex", e);
        }
    }

    /** Rebuilds the index from the manifests themselves, for when the index is lost or unreadable. */
    public static BackupIndex rebuildFrom(List<Manifest> manifests) {
        List<IndexEntry> out = new ArrayList<>();
        for (Manifest m : manifests) {
            String kind = m.version.contains("prerestore") ? "prerestore" : "manual";
            out.add(new IndexEntry(m.version, m.createdAtMs, m.totalBytes(),
                    "prerestore".equals(kind), kind));
        }
        return new BackupIndex(out);
    }
}
