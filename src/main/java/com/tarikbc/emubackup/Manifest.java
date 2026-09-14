package com.tarikbc.emubackup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The record of one backup version. See {@code FORMAT.md} section 6.
 *
 * <p>Written pretty-printed and readable on purpose: a person must be able to open it and
 * understand what was backed up, from where, and which archives hold which bytes, without
 * running this app. At roughly 250 KB against hundreds of megabytes of payload, the cost of
 * being legible is nothing.
 */
public final class Manifest {

    public static final int MANIFEST_VERSION = 1;

    public final String version;
    public final long createdAtMs;
    public final String appVersionName;
    public final int registryVersion;
    public final String registryOverrideSha256;
    public final String deviceModel;
    public final int androidSdk;
    public final String extRoot;
    public final Capabilities capabilities;
    public final List<ManifestTarget> targets;

    public Manifest(String version, long createdAtMs, String appVersionName, int registryVersion,
                    String registryOverrideSha256, String deviceModel, int androidSdk,
                    String extRoot, Capabilities capabilities, List<ManifestTarget> targets) {
        this.version = version;
        this.createdAtMs = createdAtMs;
        this.appVersionName = appVersionName;
        this.registryVersion = registryVersion;
        this.registryOverrideSha256 = registryOverrideSha256;
        this.deviceModel = deviceModel;
        this.androidSdk = androidSdk;
        this.extRoot = extRoot;
        this.capabilities = capabilities;
        this.targets = Collections.unmodifiableList(targets);
    }

    public ManifestTarget target(String id) {
        for (ManifestTarget t : targets) if (t.id.equals(id)) return t;
        return null;
    }

    /** The previous file list for a target, for {@link DiffEngine}. Null when it is not present. */
    public List<ManifestFile> filesOf(String targetId) {
        ManifestTarget t = target(targetId);
        return t == null ? null : t.files;
    }

    public long totalBytes() {
        long n = 0;
        for (ManifestTarget t : targets) n += t.totalBytes();
        return n;
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
            o.put("manifestVersion", MANIFEST_VERSION);
            o.put("version", version);
            o.put("createdAtMs", createdAtMs);
            o.put("createdAtIso", iso8601(createdAtMs));
            o.put("appVersionName", appVersionName);
            o.put("registryVersion", registryVersion);
            o.put("registryOverrideSha256", registryOverrideSha256 == null
                    ? JSONObject.NULL : registryOverrideSha256);

            JSONObject dev = new JSONObject();
            dev.put("model", deviceModel);
            dev.put("androidSdk", androidSdk);
            o.put("device", dev);
            o.put("extRoot", extRoot);

            JSONObject caps = new JSONObject();
            caps.put("allFiles", capabilities.allFiles);
            caps.put("appPrivate", capabilities.appPrivate);
            caps.put("drive", capabilities.drive);
            o.put("capabilities", caps);

            JSONArray ts = new JSONArray();
            for (ManifestTarget t : targets) ts.put(t.toJson());
            o.put("targets", ts);

            return o.toString(2);
            } catch (org.json.JSONException e) {
            throw new IllegalStateException("malformed JSON in Manifest", e);
        }
    }

    public static Manifest fromJson(String json) {
        try {
            JSONObject o = new JSONObject(json);
            int mv = o.getInt("manifestVersion");
            if (mv > MANIFEST_VERSION) {
                // Refused rather than guessed. A manifest from a newer build may describe an archive
                // layout this one cannot reconstruct, and a half-understood restore is worse than none.
                throw new IllegalArgumentException("manifestVersion " + mv
                        + " is newer than this build supports (" + MANIFEST_VERSION + ")");
            }
            JSONObject dev = o.optJSONObject("device");
            JSONObject caps = o.optJSONObject("capabilities");
            List<ManifestTarget> targets = new ArrayList<>();
            JSONArray ts = o.optJSONArray("targets");
            if (ts != null) for (int i = 0; i < ts.length(); i++) {
                targets.add(ManifestTarget.fromJson(ts.getJSONObject(i)));
            }
            return new Manifest(
                    o.getString("version"), o.getLong("createdAtMs"),
                    o.optString("appVersionName", null), o.optInt("registryVersion", 0),
                    o.isNull("registryOverrideSha256") ? null : o.optString("registryOverrideSha256", null),
                    dev == null ? null : dev.optString("model", null),
                    dev == null ? 0 : dev.optInt("androidSdk", 0),
                    o.optString("extRoot", null),
                    new Capabilities(caps != null && caps.optBoolean("allFiles"),
                            caps != null && caps.optBoolean("appPrivate"),
                            caps != null && caps.optBoolean("drive")),
                    targets);
            } catch (org.json.JSONException e) {
            throw new IllegalStateException("malformed JSON in Manifest", e);
        }
    }

    static String iso8601(long epochMs) {
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date(epochMs));
    }
}
