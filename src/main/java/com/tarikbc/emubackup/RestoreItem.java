package com.tarikbc.emubackup;

/** One file in a restore plan: what the backup holds, what the device holds, and the verdict. */
public final class RestoreItem {

    public final String path;
    public final RestoreAction action;

    /** The backup's entry. Null for {@link RestoreAction#ORPHAN_ON_DEVICE}. */
    public final ManifestFile backup;

    /** The device's current file. Null for {@link RestoreAction#CREATE}. */
    public final FileStat device;

    public RestoreItem(String path, RestoreAction action, ManifestFile backup, FileStat device) {
        this.path = path;
        this.action = action;
        this.backup = backup;
        this.device = device;
    }

    public long size() {
        return backup != null ? backup.size : (device != null ? device.size : 0);
    }

    @Override public String toString() {
        return action + " " + path;
    }
}
