package com.tarikbc.emubackup;

import java.util.regex.Pattern;

/**
 * How one target decomposes into per-game and per-profile groups.
 *
 * <p>Exactly one of {@link #pattern} or {@link #filenameRegex} is set. A target with no
 * grouping at all uses {@code null} rather than an instance of this class, and that is a
 * first-class answer: a PS2 memory card is one opaque binary holding every game's saves, and
 * splitting it would need a PS2 filesystem parser. A fake per-game view that silently
 * restores the wrong data is far worse than an honest "whole card only".
 *
 * <p>See {@code TARGETS.md} and {@code ARCHITECTURE.md} section 9.
 */
public final class Grouping {

    /** Set when grouping keys come from directory structure. Null if {@link #filenameRegex} is set. */
    public final PathPattern pattern;

    /** Set when grouping keys are embedded in filenames. Null if {@link #pattern} is set. */
    public final Pattern filenameRegex;

    public final IdKind gameIdKind;

    /** Null for targets that have games but no profile axis. */
    public final IdKind profileIdKind;

    /** Shown in the UI when the grouping is coarse or surprising. May be null. */
    public final String note;

    private Grouping(PathPattern pattern, Pattern filenameRegex,
                     IdKind gameIdKind, IdKind profileIdKind, String note) {
        this.pattern = pattern;
        this.filenameRegex = filenameRegex;
        this.gameIdKind = gameIdKind;
        this.profileIdKind = profileIdKind;
        this.note = note;
    }

    public static Grouping ofPattern(PathPattern pattern, IdKind gameIdKind, IdKind profileIdKind, String note) {
        if (pattern == null) throw new IllegalArgumentException("pattern required");
        if (gameIdKind == null) throw new IllegalArgumentException("gameIdKind required");
        if (!pattern.captureNames().contains("game")) {
            // Without a {game} capture there is nothing to group by, and the rule would
            // silently produce one group per target — indistinguishable from grouping:null
            // but far harder to notice.
            throw new IllegalArgumentException("pattern must capture {game}: " + pattern);
        }
        if (profileIdKind != null && !pattern.captureNames().contains("profile")) {
            throw new IllegalArgumentException("profileIdKind set but pattern has no {profile}: " + pattern);
        }
        return new Grouping(pattern, null, gameIdKind, profileIdKind, note);
    }

    public static Grouping ofFilenameRegex(Pattern regex, IdKind gameIdKind, String note) {
        if (regex == null) throw new IllegalArgumentException("regex required");
        if (gameIdKind == null) throw new IllegalArgumentException("gameIdKind required");
        if (!regex.pattern().contains("(?<game>")) {
            throw new IllegalArgumentException("filenameRegex must contain a (?<game>...) group: " + regex);
        }
        return new Grouping(null, regex, gameIdKind, null, note);
    }

    public boolean hasProfileAxis() {
        return profileIdKind != null;
    }
}
