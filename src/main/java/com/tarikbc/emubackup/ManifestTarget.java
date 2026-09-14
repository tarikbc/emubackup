package com.tarikbc.emubackup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One target's entry in a manifest. See {@code FORMAT.md} section 6.
 *
 * <p>{@link #chain} lists the archives that must be extracted, in order, to reconstruct this
 * target. It is written out explicitly rather than derived at restore time so that a person
 * reading the manifest by hand can follow it without reimplementing the chaining rules.
 */
public final class ManifestTarget {

    public final String id;
    public final String emulator;
    public final Tier tier;
    public final Category category;

    /** Absolute path this target was read from, and would be restored to. */
    public final String root;

    public final TargetStatus status;

    /** {@code "full"}, {@code "incremental"}, or {@code "unchanged"} when no archive was written. */
    public final String mode;

    /** The version this incremental builds on, or null. */
    public final String basis;

    /** Archive filename inside this version's directory, or null when nothing was written. */
    public final String archive;
    public final String archiveSha256;
    public final long archiveBytes;

    /** Size of the full archive at the base of this chain. */
    public final long baseFullBytes;

    /** Increments written since that full, this one included. Together with baseFullBytes this
     *  lets ArchivePolicy decide on a rebase without reading older manifests back. */
    public final long chainIncBytes;

    /** Archives to extract in order, as {@code "<version>/<archive>"}. */
    public final List<String> chain;

    public final String emulatorVersionName;
    public final long emulatorVersionCode;

    public final List<ManifestFile> files;
    public final List<String> deleted;

    /** Why a target produced nothing, when that needs explaining. */
    public final String detail;

    public ManifestTarget(String id, String emulator, Tier tier, Category category, String root,
                          TargetStatus status, String mode, String basis, String archive,
                          String archiveSha256, long archiveBytes, long baseFullBytes,
                          long chainIncBytes, List<String> chain,
                          String emulatorVersionName, long emulatorVersionCode,
                          List<ManifestFile> files, List<String> deleted, String detail) {
        this.id = id;
        this.emulator = emulator;
        this.tier = tier;
        this.category = category;
        this.root = root;
        this.status = status;
        this.mode = mode;
        this.basis = basis;
        this.archive = archive;
        this.archiveSha256 = archiveSha256;
        this.archiveBytes = archiveBytes;
        this.baseFullBytes = baseFullBytes;
        this.chainIncBytes = chainIncBytes;
        this.chain = Collections.unmodifiableList(chain);
        this.emulatorVersionName = emulatorVersionName;
        this.emulatorVersionCode = emulatorVersionCode;
        this.files = Collections.unmodifiableList(files);
        this.deleted = Collections.unmodifiableList(deleted);
        this.detail = detail;
    }

    public long totalBytes() {
        long n = 0;
        for (ManifestFile f : files) n += f.size;
        return n;
    }

    /*
     * Android's org.json declares checked JSONException on put() and the getters, while the
     * reference org.json in libs/json.jar does not. Anything shared between the JVM tests and the
     * APK therefore has to handle it, or the tests pass while the build fails — the same trap as
     * JSONObject.keySet(). These objects are built by this app from its own data, so a failure
     * here is a programming error, not a data error, and is rethrown unchecked.
     */
    JSONObject toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("emulator", emulator);
            o.put("tier", tier.name());
            o.put("category", category.name());
            o.put("root", root);
            o.put("status", status.name().toLowerCase(java.util.Locale.ROOT));
            o.put("mode", mode);
            if (basis != null) o.put("basis", basis);
            if (archive != null) {
                o.put("archive", archive);
                o.put("archiveSha256", archiveSha256);
                o.put("archiveBytes", archiveBytes);
            }
            if (baseFullBytes > 0) o.put("baseFullBytes", baseFullBytes);
            if (chainIncBytes > 0) o.put("chainIncBytes", chainIncBytes);
            o.put("chain", new JSONArray(chain));
            if (emulatorVersionName != null) o.put("emulatorVersionName", emulatorVersionName);
            if (emulatorVersionCode >= 0) o.put("emulatorVersionCode", emulatorVersionCode);

            JSONObject totals = new JSONObject();
            totals.put("files", files.size());
            totals.put("bytes", totalBytes());
            o.put("totals", totals);

            JSONArray fs = new JSONArray();
            for (ManifestFile f : files) {
                JSONObject j = new JSONObject();
                // Short keys: roughly a thousand of these per manifest, and the file stays readable.
                j.put("p", f.path);
                j.put("s", f.size);
                j.put("m", f.mtimeMs);
                j.put("h", f.sha256);
                j.put("v", f.version);
                fs.put(j);
            }
            o.put("files", fs);
            o.put("deleted", new JSONArray(deleted));
            if (detail != null) o.put("detail", detail);
            return o;
            } catch (org.json.JSONException e) {
            throw new IllegalStateException("malformed JSON in ManifestTarget", e);
        }
    }

    static ManifestTarget fromJson(JSONObject o) {
        try {
            List<ManifestFile> files = new ArrayList<>();
            JSONArray fs = o.optJSONArray("files");
            if (fs != null) {
                for (int i = 0; i < fs.length(); i++) {
                    JSONObject j = fs.getJSONObject(i);
                    files.add(new ManifestFile(j.getString("p"), j.getLong("s"), j.getLong("m"),
                            j.getString("h"), j.getString("v")));
                }
            }
            List<String> chain = strings(o.optJSONArray("chain"));
            List<String> deleted = strings(o.optJSONArray("deleted"));
            return new ManifestTarget(
                    o.getString("id"), o.optString("emulator", null),
                    Tier.valueOf(o.getString("tier")), Category.valueOf(o.getString("category")),
                    o.getString("root"),
                    TargetStatus.valueOf(o.getString("status").toUpperCase(java.util.Locale.ROOT)),
                    o.optString("mode", "unchanged"), o.has("basis") ? o.getString("basis") : null,
                    o.has("archive") ? o.getString("archive") : null,
                    o.optString("archiveSha256", null), o.optLong("archiveBytes", 0),
                    o.optLong("baseFullBytes", 0), o.optLong("chainIncBytes", 0),
                    chain, o.optString("emulatorVersionName", null), o.optLong("emulatorVersionCode", -1),
                    files, deleted, o.optString("detail", null));
            } catch (org.json.JSONException e) {
            throw new IllegalStateException("malformed JSON in ManifestTarget", e);
        }
    }

    private static List<String> strings(JSONArray a) {
        List<String> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            // optString rather than getString: it exists with the same behaviour in both the
            // Android and reference org.json, and avoids the checked exception entirely.
            String v = a.optString(i, null);
            if (v != null) out.add(v);
        }
        return out;
    }
}
