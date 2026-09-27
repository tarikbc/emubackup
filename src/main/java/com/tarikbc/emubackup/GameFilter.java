package com.tarikbc.emubackup;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Whether a game row answers a typed search.
 *
 * <p>Case and accents are ignored on both sides, and every word of the query must appear in
 * the game's name or its profile's name, so "kart 8" finds Mario Kart 8 Deluxe and "tarik"
 * finds every save on that profile. Android-free; see {@code test.sh}.
 */
public final class GameFilter {

    private GameFilter() {}

    public static boolean matches(String name, String profile, String query) {
        if (query == null) return true;
        String q = fold(query).trim();
        if (q.isEmpty()) return true;
        String hay = fold(name == null ? "" : name) + " " + fold(profile == null ? "" : profile);
        for (String word : q.split("\\s+")) {
            if (!hay.contains(word)) return false;
        }
        return true;
    }

    static String fold(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }
}
