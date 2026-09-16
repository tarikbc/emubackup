package com.tarikbc.emubackup;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a save folder's identifier into something a person can act on.
 *
 * <p>Three layers, highest priority first: names derived from the user's own ROM library, a small
 * hand-written seed for a first launch, and finally the raw identifier itself. The last layer is
 * why {@link #lookup} returns a string rather than null — an unknown id must render as itself
 * rather than blocking, erroring, or hiding the save behind it.
 *
 * <p>Android-free by design; see {@code test.sh}.
 */
public final class GameNames {

    private final Map<String, String> names;

    private GameNames(Map<String, String> names) {
        this.names = names;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static GameNames empty() {
        return new GameNames(new HashMap<>());
    }

    /** The display name for an identifier, or the identifier itself when it is not known. */
    public String lookup(IdKind kind, String id) {
        if (id == null || id.isEmpty()) return "";
        String v = names.get(key(kind, id));
        return v != null ? v : id;
    }

    public boolean isKnown(IdKind kind, String id) {
        return id != null && names.containsKey(key(kind, id));
    }

    public int size() {
        return names.size();
    }

    private static String key(IdKind kind, String id) {
        return (kind == null ? "?" : kind.name()) + "|"
                + (kind == IdKind.ROM_BASENAME ? id : id.toUpperCase(Locale.ROOT));
    }

    public static final class Builder {
        private final Map<String, String> seed = new HashMap<>();
        private final Map<String, String> derived = new HashMap<>();

        /** A hand-written fallback, used only when nothing was derived for this id. */
        public Builder seed(IdKind kind, String id, String name) {
            if (id != null && name != null) seed.put(key(kind, id), name);
            return this;
        }

        /** Derived from the user's own library. Wins over a seed entry. */
        public Builder derived(IdKind kind, String id, String name) {
            if (id != null && name != null && !name.isEmpty()) derived.put(key(kind, id), name);
            return this;
        }

        public Builder derivedFrom(RomFilenameParser.Rom rom) {
            for (Map.Entry<IdKind, String> e : rom.ids.entrySet()) {
                derived(e.getKey(), e.getValue(), rom.displayName);
            }
            // Also registered under its own cleaned name, so platforms keyed by basename resolve.
            derived(IdKind.ROM_BASENAME, rom.displayName, rom.displayName);
            return this;
        }

        public GameNames build() {
            Map<String, String> merged = new HashMap<>(seed);
            merged.putAll(derived);
            return new GameNames(merged);
        }
    }
}
