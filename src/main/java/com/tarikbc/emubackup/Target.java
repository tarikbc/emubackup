package com.tarikbc.emubackup;

import java.util.Collections;
import java.util.List;

/**
 * One backup unit: a root, the globs that select files under it, and the policy that governs
 * them. Immutable.
 *
 * <p>Built only by {@link TargetRegistry}, which validates every cross-cutting invariant.
 * See {@code TARGETS.md} for the field reference.
 */
public final class Target {

    public final String id;
    public final String label;
    public final Category category;
    public final Tier tier;

    /** The owning package for a {@link Tier#APP_PRIVATE} target; null for shared storage. */
    public final String pkg;

    /** Unresolved template, beginning {@code {EXT}} or {@code {DATA}}. */
    public final String root;

    public final List<String> include;
    public final List<String> exclude;
    public final boolean recursive;
    public final boolean caseInsensitive;

    /** Hard cap. A target over it is {@link TargetStatus#OVER_CAP} and archives nothing. */
    public final long maxBytes;

    /** {@code "deflate"} or {@code "store"}. */
    public final String compress;

    public final boolean enabledByDefault;
    public final boolean sensitive;
    public final boolean critical;

    /** Target ids that must be restored alongside this one. */
    public final List<String> coupledWith;

    /** Null when the target cannot meaningfully be split per game. */
    public final Grouping grouping;

    public final String note;

    /** Compiled at construction so a bad glob fails at parse time, not mid-backup. */
    private final PathMatcher matcher;

    Target(String id, String label, Category category, Tier tier, String pkg, String root,
           List<String> include, List<String> exclude, boolean recursive, boolean caseInsensitive,
           long maxBytes, String compress, boolean enabledByDefault, boolean sensitive,
           boolean critical, List<String> coupledWith, Grouping grouping, String note) {
        this.id = id;
        this.label = label;
        this.category = category;
        this.tier = tier;
        this.pkg = pkg;
        this.root = root;
        this.include = Collections.unmodifiableList(include);
        this.exclude = Collections.unmodifiableList(exclude);
        this.recursive = recursive;
        this.caseInsensitive = caseInsensitive;
        this.maxBytes = maxBytes;
        this.compress = compress;
        this.enabledByDefault = enabledByDefault;
        this.sensitive = sensitive;
        this.critical = critical;
        this.coupledWith = Collections.unmodifiableList(coupledWith);
        this.grouping = grouping;
        this.note = note;
        this.matcher = PathMatcher.compile(include, exclude, recursive, caseInsensitive);
    }

    public PathMatcher matcher() {
        return matcher;
    }

    /** Whether {@code store} was requested, i.e. the payload is already compressed. */
    public boolean storeOnly() {
        return "store".equals(compress);
    }

    @Override public String toString() {
        return id + " (" + category + "/" + tier + " " + root + ")";
    }
}
