package com.tarikbc.emubackup;

/**
 * Expands the {@code {EXT}} and {@code {DATA}} variables used by registry roots, and
 * normalises paths.
 *
 * <p>The registry never writes a literal {@code /sdcard}. That indirection is what keeps a
 * future removable-storage sink additive rather than a rewrite, and it is what lets the
 * whole scan pipeline be tested against a temp directory on a JVM.
 *
 * <p>Android-free by design; see {@code test.sh}.
 */
public final class PathResolver {

    public static final String EXT_VAR = "{EXT}";
    public static final String DATA_VAR = "{DATA}";

    private final String extRoot;

    /** @param extRoot primary external storage root, e.g. {@code /storage/emulated/0} */
    public PathResolver(String extRoot) {
        if (extRoot == null || extRoot.isEmpty()) {
            throw new IllegalArgumentException("extRoot must not be empty");
        }
        this.extRoot = stripTrailingSlash(normalize(extRoot));
    }

    public String extRoot() {
        return extRoot;
    }

    /**
     * Resolves a registry root template to an absolute path.
     *
     * @param template must begin with {@code {EXT}} or {@code {DATA}}
     * @param pkg      the owning package, required for {@code {DATA}}, ignored otherwise
     * @throws IllegalArgumentException if the template uses an unknown variable, omits a
     *         required package, or contains a {@code ..} segment. The traversal check is not
     *         paranoia: the registry can be overridden by a user-supplied file at
     *         {@code <ext>/EmuBackup/targets.local.json}, so a root is untrusted input.
     */
    public String resolve(String template, String pkg) {
        if (template == null || template.isEmpty()) {
            throw new IllegalArgumentException("root must not be empty");
        }
        String out;
        if (template.equals(DATA_VAR) || template.startsWith(DATA_VAR + "/")) {
            if (pkg == null || pkg.isEmpty()) {
                throw new IllegalArgumentException("root uses " + DATA_VAR + " but no pkg was given: " + template);
            }
            out = extRoot + "/Android/data/" + pkg + template.substring(DATA_VAR.length());
        } else if (template.equals(EXT_VAR) || template.startsWith(EXT_VAR + "/")) {
            out = extRoot + template.substring(EXT_VAR.length());
        } else {
            throw new IllegalArgumentException("root must start with " + EXT_VAR + " or " + DATA_VAR + ": " + template);
        }
        out = stripTrailingSlash(normalize(out));
        requireNoTraversal(out);
        return out;
    }

    /** Collapses repeated separators. Does not resolve {@code .} or {@code ..}; see {@link #resolve}. */
    public static String normalize(String path) {
        if (path == null) return null;
        StringBuilder sb = new StringBuilder(path.length());
        boolean lastSlash = false;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '/') {
                if (!lastSlash) sb.append(c);
                lastSlash = true;
            } else {
                sb.append(c);
                lastSlash = false;
            }
        }
        return sb.toString();
    }

    /** Joins a root and a target-relative path. */
    public static String join(String base, String rel) {
        if (rel == null || rel.isEmpty()) return stripTrailingSlash(base);
        return stripTrailingSlash(base) + "/" + stripLeadingSlash(normalize(rel));
    }

    /**
     * Returns {@code abs} expressed relative to {@code root}, with no leading slash.
     *
     * @throws IllegalArgumentException if {@code abs} is not under {@code root}. Silently
     *         returning the absolute path here would put absolute entries in a zip, which
     *         breaks hand-restore.
     */
    public static String relativize(String root, String abs) {
        String r = stripTrailingSlash(normalize(root));
        String a = normalize(abs);
        if (a.equals(r)) return "";
        if (!a.startsWith(r + "/")) {
            throw new IllegalArgumentException(abs + " is not under " + root);
        }
        return a.substring(r.length() + 1);
    }

    private static void requireNoTraversal(String p) {
        for (String seg : p.split("/", -1)) {
            if (seg.equals("..")) throw new IllegalArgumentException("path traversal in root: " + p);
        }
    }

    private static String stripTrailingSlash(String p) {
        if (p == null || p.length() <= 1) return p;
        int end = p.length();
        while (end > 1 && p.charAt(end - 1) == '/') end--;
        return p.substring(0, end);
    }

    private static String stripLeadingSlash(String p) {
        int i = 0;
        while (i < p.length() && p.charAt(i) == '/') i++;
        return p.substring(i);
    }
}
