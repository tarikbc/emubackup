package com.tarikbc.emubackup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What restoring one target would do, file by file. Immutable. */
public final class RestorePlan {

    public final String targetId;
    public final String targetLabel;
    public final String root;
    public final Tier tier;
    public final List<RestoreItem> items;

    /** Set when the user has chosen to overwrite files that are newer on the device. */
    public final boolean forced;

    public RestorePlan(String targetId, String targetLabel, String root, Tier tier,
                       List<RestoreItem> items, boolean forced) {
        this.targetId = targetId;
        this.targetLabel = targetLabel;
        this.root = root;
        this.tier = tier;
        this.items = Collections.unmodifiableList(items);
        this.forced = forced;
    }

    public Map<RestoreAction, Integer> counts() {
        Map<RestoreAction, Integer> out = new LinkedHashMap<>();
        for (RestoreAction a : RestoreAction.values()) out.put(a, 0);
        for (RestoreItem i : items) out.put(i.action, out.get(i.action) + 1);
        return out;
    }

    public int count(RestoreAction a) {
        int n = 0;
        for (RestoreItem i : items) if (i.action == a) n++;
        return n;
    }

    /** The files this plan would actually write, honouring {@link #forced}. */
    public List<RestoreItem> toWrite() {
        List<RestoreItem> out = new ArrayList<>();
        for (RestoreItem i : items) {
            if (i.action.appliesByDefault() || (forced && i.action.forceable())) out.add(i);
        }
        return out;
    }

    public long bytesToWrite() {
        long n = 0;
        for (RestoreItem i : toWrite()) n += i.size();
        return n;
    }

    /** Files that would be replaced, and so must go into the pre-restore snapshot first. */
    public List<RestoreItem> toOverwrite() {
        List<RestoreItem> out = new ArrayList<>();
        for (RestoreItem i : toWrite()) if (i.device != null) out.add(i);
        return out;
    }

    public long newestConflictMtime() {
        long n = 0;
        for (RestoreItem i : items) {
            if (i.action == RestoreAction.CONFLICT_NEWER && i.device != null) {
                n = Math.max(n, i.device.mtimeMs);
            }
        }
        return n;
    }

    public boolean isNoOp() {
        return toWrite().isEmpty();
    }

    public RestorePlan withForced(boolean f) {
        return new RestorePlan(targetId, targetLabel, root, tier, items, f);
    }
}
