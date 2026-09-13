package com.tarikbc.emubackup;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Parses and validates {@code assets/targets.json} — the complete list of what EmuBackup
 * reads and writes.
 *
 * <p>The registry is data rather than code for one reason above all: CI can validate the
 * shipped artifact. {@code TargetRegistryTest} parses the real asset off disk and asserts
 * every invariant below, so a malformed glob, a duplicate id, a save-state target left
 * enabled by default, or a keys target not marked sensitive <em>fails the build</em>. In a
 * hand-written Java registry all of those compile cleanly.
 *
 * <p>Parsing is strict. A missing required key, an unknown enum value, or a broken
 * cross-reference throws {@link RegistryException} rather than being defaulted, because a
 * quietly half-loaded registry means a quietly incomplete backup. Unknown <em>keys</em> are
 * the one tolerated case: they are collected into {@link #warnings()} so a newer registry
 * stays loadable on an older build.
 *
 * <p>Android-free by design; see {@code test.sh}.
 */
public final class TargetRegistry {

    /** The highest {@code registryVersion} this build understands. */
    public static final int SUPPORTED_VERSION = 1;

    private static final Set<String> EMULATOR_KEYS =
            new HashSet<>(Arrays.asList("id", "label", "packages", "targets"));
    private static final Set<String> TARGET_KEYS = new HashSet<>(Arrays.asList(
            "id", "label", "category", "tier", "pkg", "root", "include", "exclude",
            "recursive", "caseInsensitive", "maxBytes", "compress", "enabledByDefault",
            "sensitive", "critical", "coupledWith", "grouping", "note"));
    private static final Set<String> GROUPING_KEYS = new HashSet<>(Arrays.asList(
            "pattern", "filenameRegex", "gameIdKind", "profileIdKind", "note"));

    /** Thrown for any structural or semantic problem in a registry document. */
    public static final class RegistryException extends RuntimeException {
        public RegistryException(String msg) { super(msg); }
        public RegistryException(String msg, Throwable cause) { super(msg, cause); }
    }

    private final int registryVersion;
    private final List<Emulator> emulators;
    private final Map<String, Target> targetsById;
    private final List<String> warnings;

    private TargetRegistry(int registryVersion, List<Emulator> emulators,
                           Map<String, Target> targetsById, List<String> warnings) {
        this.registryVersion = registryVersion;
        this.emulators = Collections.unmodifiableList(emulators);
        this.targetsById = Collections.unmodifiableMap(targetsById);
        this.warnings = Collections.unmodifiableList(warnings);
    }

    // ---------------------------------------------------------------- parsing

    public static TargetRegistry parse(String json) {
        return parseWithOverride(json, null);
    }

    /**
     * Parses the bundled registry, then applies a user-supplied override if present.
     *
     * <p>An override emulator replaces the bundled emulator with the same id outright; an
     * emulator id not in the bundled set is appended. Whole-emulator replacement is chosen
     * over per-field merging because merge semantics for lists ("does this include list
     * replace or extend?") are exactly the kind of ambiguity that produces a backup the
     * user did not intend.
     *
     * <p>This exists because emulators move their save paths without warning — which is what
     * caused the data loss this app was written for — and waiting for a release is the wrong
     * answer when the data is already at risk.
     *
     * @param overrideJson may be null or blank
     */
    public static TargetRegistry parseWithOverride(String json, String overrideJson) {
        List<String> warnings = new ArrayList<>();
        JSONObject bundled = readObject(json, "bundled registry");
        int version = requireInt(bundled, "registryVersion", "registry root");
        if (version > SUPPORTED_VERSION) {
            throw new RegistryException("registryVersion " + version
                    + " is newer than this build supports (" + SUPPORTED_VERSION + ")");
        }
        noteUnknownKeys(bundled, new HashSet<>(Arrays.asList("registryVersion", "emulators")),
                "registry root", warnings);

        Map<String, JSONObject> byId = new LinkedHashMap<>();
        JSONArray arr = requireArray(bundled, "emulators", "registry root");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = requireObjectAt(arr, i, "emulators");
            String id = requireString(e, "id", "emulators[" + i + "]");
            if (byId.put(id, e) != null) {
                throw new RegistryException("duplicate emulator id: " + id);
            }
        }

        if (overrideJson != null && !overrideJson.trim().isEmpty()) {
            JSONObject over = readObject(overrideJson, "registry override");
            int ov = requireInt(over, "registryVersion", "override root");
            if (ov > SUPPORTED_VERSION) {
                throw new RegistryException("override registryVersion " + ov
                        + " is newer than this build supports (" + SUPPORTED_VERSION + ")");
            }
            JSONArray oarr = requireArray(over, "emulators", "override root");
            for (int i = 0; i < oarr.length(); i++) {
                JSONObject e = requireObjectAt(oarr, i, "override emulators");
                String id = requireString(e, "id", "override emulators[" + i + "]");
                warnings.add(byId.containsKey(id)
                        ? "override replaces bundled emulator '" + id + "'"
                        : "override adds emulator '" + id + "'");
                byId.put(id, e);
            }
        }

        List<Emulator> emulators = new ArrayList<>();
        Map<String, Target> targetsById = new LinkedHashMap<>();
        for (Map.Entry<String, JSONObject> en : byId.entrySet()) {
            emulators.add(parseEmulator(en.getValue(), targetsById, warnings));
        }

        validateCrossReferences(emulators, targetsById);
        return new TargetRegistry(version, emulators, targetsById, warnings);
    }

    private static Emulator parseEmulator(JSONObject e, Map<String, Target> sink, List<String> warnings) {
        String id = requireString(e, "id", "emulator");
        String where = "emulator '" + id + "'";
        noteUnknownKeys(e, EMULATOR_KEYS, where, warnings);

        String label = requireString(e, "label", where);
        List<String> packages = requireStringList(e, "packages", where);
        if (packages.isEmpty()) throw new RegistryException(where + ": packages must not be empty");

        JSONArray ts = requireArray(e, "targets", where);
        if (ts.length() == 0) throw new RegistryException(where + ": targets must not be empty");

        List<Target> targets = new ArrayList<>();
        for (int i = 0; i < ts.length(); i++) {
            Target t = parseTarget(requireObjectAt(ts, i, where + " targets"), id, packages.get(0), warnings);
            if (sink.put(t.id, t) != null) {
                throw new RegistryException("duplicate target id across the registry: " + t.id);
            }
            targets.add(t);
        }
        return new Emulator(id, label, packages, targets);
    }

    private static Target parseTarget(JSONObject o, String emulatorId, String defaultPkg, List<String> warnings) {
        String id = requireString(o, "id", "target in emulator '" + emulatorId + "'");
        String where = "target '" + id + "'";
        noteUnknownKeys(o, TARGET_KEYS, where, warnings);

        String label = requireString(o, "label", where);
        Category category = requireEnum(Category.class, requireString(o, "category", where), "category", where);
        Tier tier = requireEnum(Tier.class, requireString(o, "tier", where), "tier", where);
        String root = requireString(o, "root", where);

        // Root form and tier must agree. A {DATA} root is by definition inside another app's
        // private directory, and a {EXT} root by definition is not; letting them disagree
        // would route a target through the wrong FileSource and silently read nothing.
        boolean isData = root.equals(PathResolver.DATA_VAR) || root.startsWith(PathResolver.DATA_VAR + "/");
        boolean isExt = root.equals(PathResolver.EXT_VAR) || root.startsWith(PathResolver.EXT_VAR + "/");
        if (!isData && !isExt) {
            throw new RegistryException(where + ": root must start with "
                    + PathResolver.EXT_VAR + " or " + PathResolver.DATA_VAR + ", got: " + root);
        }
        String pkg = o.has("pkg") && !o.isNull("pkg") ? requireString(o, "pkg", where) : null;
        if (isData) {
            if (tier != Tier.APP_PRIVATE) {
                throw new RegistryException(where + ": a " + PathResolver.DATA_VAR
                        + " root requires tier APP_PRIVATE, got " + tier);
            }
            if (pkg == null) {
                throw new RegistryException(where + ": a " + PathResolver.DATA_VAR + " root requires 'pkg'");
            }
        } else if (tier != Tier.SHARED) {
            throw new RegistryException(where + ": a " + PathResolver.EXT_VAR
                    + " root requires tier SHARED, got " + tier);
        }

        List<String> include = requireStringList(o, "include", where);
        if (include.isEmpty()) {
            throw new RegistryException(where + ": include must not be empty — an empty include "
                    + "list means 'match nothing', which is never intended");
        }
        List<String> exclude = o.has("exclude") ? requireStringList(o, "exclude", where) : new ArrayList<>();

        boolean recursive = o.optBoolean("recursive", true);
        if (!recursive) {
            for (String g : include) {
                if (g.contains("**")) {
                    throw new RegistryException(where + ": include glob '" + g + "' uses ** but "
                            + "recursive is false, which can never match");
                }
            }
        }
        boolean caseInsensitive = o.optBoolean("caseInsensitive", false);

        long maxBytes = requireLong(o, "maxBytes", where);
        if (maxBytes <= 0) throw new RegistryException(where + ": maxBytes must be positive");

        String compress = o.optString("compress", "deflate");
        if (!compress.equals("deflate") && !compress.equals("store")) {
            throw new RegistryException(where + ": compress must be 'deflate' or 'store', got: " + compress);
        }

        boolean enabledByDefault = o.optBoolean("enabledByDefault", true);
        boolean sensitive = o.optBoolean("sensitive", false);
        boolean critical = o.optBoolean("critical", false);

        // The two invariants that protect the user from their own backup growing or leaking.
        if (category.optInOnly() && enabledByDefault) {
            throw new RegistryException(where + ": category " + category
                    + " must have enabledByDefault false");
        }
        if (category == Category.KEY && !sensitive) {
            throw new RegistryException(where + ": a KEY target must be marked sensitive");
        }

        List<String> coupledWith = o.has("coupledWith") ? requireStringList(o, "coupledWith", where) : new ArrayList<>();
        if (coupledWith.contains(id)) throw new RegistryException(where + ": coupledWith must not list itself");

        Grouping grouping = parseGrouping(o, where, warnings);
        String note = o.optString("note", null);

        try {
            return new Target(id, label, category, tier, pkg, root, include, exclude, recursive,
                    caseInsensitive, maxBytes, compress, enabledByDefault, sensitive, critical,
                    coupledWith, grouping, note);
        } catch (IllegalArgumentException ex) {
            throw new RegistryException(where + ": " + ex.getMessage(), ex);
        }
    }

    private static Grouping parseGrouping(JSONObject o, String where, List<String> warnings) {
        if (!o.has("grouping") || o.isNull("grouping")) return null;
        JSONObject g = o.optJSONObject("grouping");
        if (g == null) throw new RegistryException(where + ": grouping must be an object or null");
        noteUnknownKeys(g, GROUPING_KEYS, where + " grouping", warnings);

        boolean hasPattern = g.has("pattern") && !g.isNull("pattern");
        boolean hasRegex = g.has("filenameRegex") && !g.isNull("filenameRegex");
        if (hasPattern == hasRegex) {
            throw new RegistryException(where + ": grouping needs exactly one of 'pattern' or 'filenameRegex'");
        }
        IdKind gameKind = requireEnum(IdKind.class, requireString(g, "gameIdKind", where + " grouping"),
                "gameIdKind", where + " grouping");
        String note = g.optString("note", null);
        try {
            if (hasPattern) {
                IdKind profileKind = g.has("profileIdKind") && !g.isNull("profileIdKind")
                        ? requireEnum(IdKind.class, g.getString("profileIdKind"), "profileIdKind", where + " grouping")
                        : null;
                return Grouping.ofPattern(PathPattern.compile(g.getString("pattern")), gameKind, profileKind, note);
            }
            if (g.has("profileIdKind") && !g.isNull("profileIdKind")) {
                throw new RegistryException(where + ": filenameRegex grouping cannot have a profile axis");
            }
            return Grouping.ofFilenameRegex(Pattern.compile(g.getString("filenameRegex")), gameKind, note);
        } catch (PatternSyntaxException ex) {
            throw new RegistryException(where + ": filenameRegex does not compile: " + ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw new RegistryException(where + ": " + ex.getMessage(), ex);
        } catch (JSONException ex) {
            throw new RegistryException(where + " grouping: " + ex.getMessage(), ex);
        }
    }

    private static void validateCrossReferences(List<Emulator> emulators, Map<String, Target> byId) {
        for (Target t : byId.values()) {
            for (String c : t.coupledWith) {
                if (!byId.containsKey(c)) {
                    throw new RegistryException("target '" + t.id + "' is coupledWith unknown target '" + c + "'");
                }
            }
        }
        // Two targets in different emulators must not cover overlapping trees. This is the
        // check that keeps the two independent 3DS save sets — Azahar in shared storage and
        // the legacy Citra install in app-private storage — from being merged. Merging them
        // would restore one emulator's saves into the other, which looks like it worked.
        for (Emulator a : emulators) {
            for (Emulator b : emulators) {
                if (a.id.compareTo(b.id) >= 0) continue;
                for (Target ta : a.targets) {
                    for (Target tb : b.targets) {
                        String ra = rootKey(ta), rb = rootKey(tb);
                        if (overlaps(ra, rb)) {
                            throw new RegistryException("targets '" + ta.id + "' and '" + tb.id
                                    + "' are in different emulators but cover overlapping roots: "
                                    + ra + " vs " + rb);
                        }
                    }
                }
            }
        }
    }

    /** Comparable root string with the package substituted, so {@code {DATA}} roots differ per app. */
    private static String rootKey(Target t) {
        return t.pkg == null ? t.root : t.root.replace(PathResolver.DATA_VAR, PathResolver.DATA_VAR + ":" + t.pkg);
    }

    private static boolean overlaps(String a, String b) {
        return a.equals(b) || a.startsWith(b + "/") || b.startsWith(a + "/");
    }

    // ---------------------------------------------------------------- lookups

    public int registryVersion() { return registryVersion; }
    public List<Emulator> emulators() { return emulators; }

    /** Non-fatal notes, chiefly unknown keys from a newer registry. Surfaced in Settings. */
    public List<String> warnings() { return warnings; }

    public List<Target> allTargets() {
        return Collections.unmodifiableList(new ArrayList<>(targetsById.values()));
    }

    public Target target(String id) {
        Target t = targetsById.get(id);
        if (t == null) throw new RegistryException("no such target: " + id);
        return t;
    }

    public boolean hasTarget(String id) { return targetsById.containsKey(id); }

    public Emulator emulator(String id) {
        for (Emulator e : emulators) if (e.id.equals(id)) return e;
        throw new RegistryException("no such emulator: " + id);
    }

    public Emulator emulatorOf(String targetId) {
        for (Emulator e : emulators) {
            for (Target t : e.targets) if (t.id.equals(targetId)) return e;
        }
        throw new RegistryException("no such target: " + targetId);
    }

    /**
     * Every target that must be restored together with the given ones, transitively.
     * Restoring Eden's saves without its profiles leaves the saves orphaned, so the
     * restore planner asks this rather than trusting the user's selection.
     */
    public Set<String> withCoupled(Set<String> targetIds) {
        Set<String> out = new LinkedHashSet<>(targetIds);
        List<String> queue = new ArrayList<>(targetIds);
        while (!queue.isEmpty()) {
            Target t = target(queue.remove(0));
            for (String c : t.coupledWith) if (out.add(c)) queue.add(c);
        }
        return out;
    }

    public List<Target> targetsOfTier(Tier tier) {
        List<Target> out = new ArrayList<>();
        for (Target t : targetsById.values()) if (t.tier == tier) out.add(t);
        return out;
    }

    public List<Target> defaultEnabledTargets() {
        List<Target> out = new ArrayList<>();
        for (Target t : targetsById.values()) if (t.enabledByDefault) out.add(t);
        return out;
    }

    // ---------------------------------------------------- strict json helpers

    private static JSONObject readObject(String json, String what) {
        if (json == null || json.trim().isEmpty()) throw new RegistryException(what + " is empty");
        try {
            return new JSONObject(json);
        } catch (JSONException e) {
            throw new RegistryException(what + " is not valid JSON: " + e.getMessage(), e);
        }
    }

    private static String requireString(JSONObject o, String key, String where) {
        if (!o.has(key) || o.isNull(key)) throw new RegistryException(where + ": missing '" + key + "'");
        try {
            String s = o.getString(key);
            if (s.isEmpty()) throw new RegistryException(where + ": '" + key + "' must not be empty");
            return s;
        } catch (JSONException e) {
            throw new RegistryException(where + ": '" + key + "' must be a string", e);
        }
    }

    private static int requireInt(JSONObject o, String key, String where) {
        if (!o.has(key) || o.isNull(key)) throw new RegistryException(where + ": missing '" + key + "'");
        try {
            return o.getInt(key);
        } catch (JSONException e) {
            throw new RegistryException(where + ": '" + key + "' must be an integer", e);
        }
    }

    private static long requireLong(JSONObject o, String key, String where) {
        if (!o.has(key) || o.isNull(key)) throw new RegistryException(where + ": missing '" + key + "'");
        try {
            return o.getLong(key);
        } catch (JSONException e) {
            throw new RegistryException(where + ": '" + key + "' must be a number", e);
        }
    }

    private static JSONArray requireArray(JSONObject o, String key, String where) {
        if (!o.has(key) || o.isNull(key)) throw new RegistryException(where + ": missing '" + key + "'");
        JSONArray a = o.optJSONArray(key);
        if (a == null) throw new RegistryException(where + ": '" + key + "' must be an array");
        return a;
    }

    private static JSONObject requireObjectAt(JSONArray a, int i, String where) {
        JSONObject o = a.optJSONObject(i);
        if (o == null) throw new RegistryException(where + "[" + i + "] must be an object");
        return o;
    }

    private static List<String> requireStringList(JSONObject o, String key, String where) {
        JSONArray a = requireArray(o, key, where);
        List<String> out = new ArrayList<>(a.length());
        for (int i = 0; i < a.length(); i++) {
            String s = a.optString(i, null);
            if (s == null || s.isEmpty()) {
                throw new RegistryException(where + ": '" + key + "[" + i + "]' must be a non-empty string");
            }
            out.add(s);
        }
        return out;
    }

    private static <E extends Enum<E>> E requireEnum(Class<E> type, String value, String key, String where) {
        for (E c : type.getEnumConstants()) if (c.name().equals(value)) return c;
        throw new RegistryException(where + ": unknown " + key + " '" + value + "'; expected one of "
                + Arrays.toString(type.getEnumConstants()));
    }

    /**
     * Collects unknown keys as warnings so a newer registry still loads on an older build.
     *
     * <p>Uses {@code keys()} rather than {@code keySet()} deliberately. The JVM test suite
     * compiles against {@code libs/json.jar}, but the APK uses the cut-down {@code org.json}
     * inside {@code android.jar}, which has no {@code keySet()}. Anything used here must
     * exist in both, or the tests pass while the build breaks.
     */
    private static void noteUnknownKeys(JSONObject o, Set<String> known, String where, List<String> warnings) {
        for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            if (!known.contains(k)) warnings.add(where + ": ignoring unknown key '" + k + "'");
        }
    }
}
