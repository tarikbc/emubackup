package com.tarikbc.emubackup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Decides which files under a target root belong to that target.
 *
 * <p>This class is the single reason EmuBackup does not copy hundreds of gigabytes of ROMs.
 * Five of the emulators on the reference device keep saves <em>inside</em> the ROM tree —
 * {@code ROMs/nds/*.sav} sits beside multi-gigabyte ROM zips, and
 * {@code RetroArch/system/} holds ~4 GB of BIOS images alongside a few kilobytes of
 * Dreamcast VMU saves. There is deliberately no "copy the whole folder" code path anywhere
 * in the app; every target must state its globs.
 *
 * <p>Glob syntax, matched against the target-relative path with {@code /} separators and no
 * leading slash:
 * <ul>
 *   <li>{@code ?} — exactly one character, never {@code /}
 *   <li>{@code *} — any run of characters within one path component
 *   <li>{@code **} — any run of characters across components
 *   <li>{@code **}{@code /} — zero or more leading directories, so {@code **}{@code /*.tmp}
 *       matches both {@code a.tmp} and {@code x/a.tmp}
 *   <li>{@code /**} at the end — the directory itself or anything beneath it
 * </ul>
 *
 * <p>{@code exclude} always beats {@code include}. See {@code TARGETS.md}.
 *
 * <p>Android-free by design; see {@code test.sh}.
 */
public final class PathMatcher {

    private static final String REGEX_META = "\\.[]{}()<>+-=!|^$?*";

    private final List<Pattern> include;
    private final List<Pattern> exclude;
    private final boolean recursive;

    private PathMatcher(List<Pattern> include, List<Pattern> exclude, boolean recursive) {
        this.include = Collections.unmodifiableList(include);
        this.exclude = Collections.unmodifiableList(exclude);
        this.recursive = recursive;
    }

    /**
     * @param include     must be non-empty; an empty include list would mean "match nothing",
     *                    which is never what a registry author intends and is rejected at
     *                    parse time instead of silently producing an empty backup
     * @param exclude     may be null or empty
     * @param recursive   when false, only direct children of the root can match. This is how
     *                    {@code RetroArch/system/} yields its VMU saves without descending
     *                    into the BIOS subtree
     * @param ignoreCase  for case-insensitive volumes such as exFAT cards
     */
    public static PathMatcher compile(List<String> include, List<String> exclude,
                                      boolean recursive, boolean ignoreCase) {
        if (include == null || include.isEmpty()) {
            throw new IllegalArgumentException("include must not be empty");
        }
        int flags = ignoreCase ? Pattern.CASE_INSENSITIVE : 0;
        List<Pattern> inc = new ArrayList<>(include.size());
        for (String g : include) inc.add(Pattern.compile(globToRegex(requireGlob(g)), flags));
        List<Pattern> exc = new ArrayList<>();
        if (exclude != null) {
            for (String g : exclude) exc.add(Pattern.compile(globToRegex(requireGlob(g)), flags));
        }
        return new PathMatcher(inc, exc, recursive);
    }

    /**
     * @param relPath target-relative, {@code /}-separated, no leading slash
     * @return whether this file belongs to the target
     */
    public boolean matches(String relPath) {
        if (relPath == null || relPath.isEmpty()) return false;
        if (!recursive && relPath.indexOf('/') >= 0) return false;
        for (Pattern p : exclude) {
            if (p.matcher(relPath).matches()) return false;
        }
        for (Pattern p : include) {
            if (p.matcher(relPath).matches()) return true;
        }
        return false;
    }

    public boolean isRecursive() {
        return recursive;
    }

    /** Exposed for {@code TargetRegistry} validation and for tests. */
    static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder(glob.length() * 2);
        int i = 0, n = glob.length();
        while (i < n) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < n && glob.charAt(i + 1) == '*') {
                    if (i + 2 < n && glob.charAt(i + 2) == '/') {
                        // "**/" — zero or more leading directories.
                        sb.append("(?:.*/)?");
                        i += 3;
                    } else {
                        sb.append(".*");
                        i += 2;
                    }
                } else {
                    sb.append("[^/]*");
                    i++;
                }
                continue;
            }
            if (c == '?') {
                sb.append("[^/]");
                i++;
                continue;
            }
            if (c == '/' && i + 3 == n && glob.charAt(i + 1) == '*' && glob.charAt(i + 2) == '*') {
                // trailing "/**" — the directory itself or anything under it.
                sb.append("(?:/.*)?");
                i += 3;
                continue;
            }
            if (REGEX_META.indexOf(c) >= 0) sb.append('\\');
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static String requireGlob(String g) {
        if (g == null || g.isEmpty()) throw new IllegalArgumentException("empty glob");
        if (g.startsWith("/")) throw new IllegalArgumentException("glob must be target-relative: " + g);
        if (g.contains("\\")) throw new IllegalArgumentException("use / in globs, not \\: " + g);
        return g;
    }
}
