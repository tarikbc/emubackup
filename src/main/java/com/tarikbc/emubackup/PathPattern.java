package com.tarikbc.emubackup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tiny path language for decomposing a target into per-game and per-profile groups.
 *
 * <p>Deliberately not regex and not positional indices. Registry authors write
 * {@code user/save/{account}/{profile}/{game}/**} and get named captures, which stays
 * readable next to the real directory layout it mirrors. Indices would silently capture the
 * wrong component the first time an emulator adds a directory level.
 *
 * <p>Rules: {@code {name}} matches exactly one path component and captures it; {@code **}
 * matches the remaining components and must be last; anything else matches literally.
 *
 * <p>Android-free by design; see {@code test.sh}.
 */
public final class PathPattern {

    private static final String WILD = "**";

    private final String raw;
    private final String[] parts;
    private final List<String> names;
    private final boolean trailingWild;

    private PathPattern(String raw, String[] parts, List<String> names, boolean trailingWild) {
        this.raw = raw;
        this.parts = parts;
        this.names = Collections.unmodifiableList(names);
        this.trailingWild = trailingWild;
    }

    public static PathPattern compile(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            throw new IllegalArgumentException("pattern must not be empty");
        }
        if (pattern.startsWith("/")) {
            throw new IllegalArgumentException("pattern must be target-relative: " + pattern);
        }
        String[] parts = pattern.split("/", -1);
        List<String> names = new ArrayList<>();
        boolean wild = false;
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty()) {
                throw new IllegalArgumentException("empty path component in pattern: " + pattern);
            }
            if (p.equals(WILD)) {
                if (i != parts.length - 1) {
                    // Allowing ** in the middle would make the match ambiguous — there would
                    // be more than one way to assign components to captures — and an
                    // ambiguous grouping rule silently mis-labels saves.
                    throw new IllegalArgumentException("** is only allowed as the last component: " + pattern);
                }
                wild = true;
                continue;
            }
            if (p.startsWith("{") && p.endsWith("}")) {
                String name = p.substring(1, p.length() - 1);
                if (name.isEmpty()) throw new IllegalArgumentException("empty capture name in: " + pattern);
                if (names.contains(name)) throw new IllegalArgumentException("duplicate capture name '" + name + "' in: " + pattern);
                names.add(name);
                continue;
            }
            if (p.indexOf('{') >= 0 || p.indexOf('}') >= 0 || p.contains(WILD)) {
                throw new IllegalArgumentException("a component is either a literal, {name}, or **: " + p);
            }
        }
        return new PathPattern(pattern, parts, names, wild);
    }

    /**
     * @param relPath target-relative, {@code /}-separated, no leading slash
     * @return the captures, or {@code null} if the path does not match. Never a partial map:
     *         a caller must not have to check whether every expected key is present.
     */
    public Map<String, String> match(String relPath) {
        if (relPath == null || relPath.isEmpty()) return null;
        String[] comps = relPath.split("/", -1);
        int fixed = trailingWild ? parts.length - 1 : parts.length;
        if (trailingWild ? comps.length < fixed : comps.length != fixed) return null;

        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < fixed; i++) {
            String p = parts[i];
            String c = comps[i];
            if (c.isEmpty()) return null;
            if (p.startsWith("{") && p.endsWith("}")) {
                out.put(p.substring(1, p.length() - 1), c);
            } else if (!p.equals(c)) {
                return null;
            }
        }
        return out;
    }

    public List<String> captureNames() {
        return names;
    }

    @Override public String toString() {
        return raw;
    }
}
