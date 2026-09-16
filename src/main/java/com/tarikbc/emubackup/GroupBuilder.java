package com.tarikbc.emubackup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a target's files into per-game, and where the layout supports it per-profile, groups.
 *
 * <p>Files that no rule matches land in a single {@link SaveGroup#UNGROUPED} group rather than
 * being dropped.
 *
 * <p>Android-free by design; see {@code test.sh}.
 */
public final class GroupBuilder {

    private static final Pattern PSP_ID = Pattern.compile("^([A-Za-z]{4}[0-9]{5})");

    public static List<SaveGroup> build(String targetId, Grouping g, List<FileStat> files) {
        if (files.isEmpty()) return new ArrayList<>();

        // A target with no grouping rule is one indivisible unit — a PS2 memory card is a single
        // binary holding every game's saves. Saying so is better than a fabricated per-game view.
        if (g == null) {
            List<SaveGroup> out = new ArrayList<>();
            out.add(new SaveGroup(targetId, null, null, null, new ArrayList<>(files)));
            return out;
        }

        Map<String, List<FileStat>> buckets = new LinkedHashMap<>();
        Map<String, String[]> keys = new LinkedHashMap<>();

        for (FileStat f : files) {
            String game = null, profile = null;

            if (g.pattern != null) {
                Map<String, String> m = g.pattern.match(f.path);
                if (m != null) {
                    game = normalise(g.gameIdKind, m.get("game"));
                    // A 3DS title id is split across two directory levels, so the pattern captures
                    // the high half separately and it is rejoined here into a real title id.
                    if (g.gameIdKind == IdKind.N3DS_TITLE_ID && m.get("hi") != null && game != null) {
                        game = (m.get("hi") + game).toUpperCase(Locale.ROOT);
                    }
                    if (g.hasProfileAxis()) profile = normalise(g.profileIdKind, m.get("profile"));
                }
            } else if (g.filenameRegex != null) {
                // Matches the basename, not the whole relative path: these keys live in filenames,
                // and a directory prefix would defeat an anchored pattern.
                Matcher m = g.filenameRegex.matcher(f.name());
                if (m.matches() || m.lookingAt()) {
                    game = normalise(g.gameIdKind, groupOrNull(m, "game"));
                }
            }

            String gameKey = (game == null || game.isEmpty()) ? SaveGroup.UNGROUPED : game;
            String bucket = (profile == null ? "" : profile + "/") + gameKey;
            buckets.computeIfAbsent(bucket, k -> new ArrayList<>()).add(f);
            keys.putIfAbsent(bucket, new String[]{profile, gameKey});
        }

        List<SaveGroup> out = new ArrayList<>(buckets.size());
        for (Map.Entry<String, List<FileStat>> e : buckets.entrySet()) {
            String[] k = keys.get(e.getKey());
            out.add(new SaveGroup(targetId, k[0], k[1], g.gameIdKind, e.getValue()));
        }
        // Newest first: the save someone wants back is almost always a recent one. Ungrouped
        // leftovers sort last, being a diagnostic rather than a choice. Written out rather than
        // composed from Comparator.comparing, where the boolean key and the negated long read
        // as a puzzle for no benefit.
        out.sort((a, b) -> {
            if (a.isUngrouped() != b.isUngrouped()) return a.isUngrouped() ? 1 : -1;
            return Long.compare(b.newestMtimeMs, a.newestMtimeMs);
        });
        return out;
    }

    private static String groupOrNull(Matcher m, String name) {
        try {
            return m.group(name);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return null;
        }
    }

    /** Trims an identifier to its canonical form so one game cannot appear as two. */
    static String normalise(IdKind kind, String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (kind == null) return s;

        switch (kind) {
            case PSP_GAME_ID: {
                // Save folders are named ULUS10336DATA00, ULUS10336SYSTEM and so on; the game is
                // the leading four letters and five digits.
                Matcher m = PSP_ID.matcher(s);
                return m.find() ? m.group(1).toUpperCase(Locale.ROOT) : s.toUpperCase(Locale.ROOT);
            }
            case SWITCH_TITLE_ID:
            case SWITCH_USER_UUID:
            case N3DS_TITLE_ID:
            case GC_GAME_ID:
            case PSX_SERIAL:
            case PS2_SERIAL:
                return s.toUpperCase(Locale.ROOT);
            case ROM_BASENAME:
            default:
                return s;
        }
    }

    private GroupBuilder() {}
}
