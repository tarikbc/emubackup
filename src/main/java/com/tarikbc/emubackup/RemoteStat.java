package com.tarikbc.emubackup;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * One file's metadata, carried across the Shizuku binder from the privileged process.
 *
 * <p>Mirrors the android-free {@code FileStat} value type used by the rest of the engine.
 * The duplication is deliberate: {@code FileStat} must stay free of {@code android.*} so
 * the scan and diff logic remains JVM-testable, and Parcelable is unavoidably an Android
 * type, so the boundary gets its own carrier and converts once.
 */
public final class RemoteStat implements Parcelable {

    public final String path;
    public final long size;
    public final long mtimeMs;
    public final boolean dir;

    public RemoteStat(String path, long size, long mtimeMs, boolean dir) {
        this.path = path;
        this.size = size;
        this.mtimeMs = mtimeMs;
        this.dir = dir;
    }

    private RemoteStat(Parcel in) {
        path = in.readString();
        size = in.readLong();
        mtimeMs = in.readLong();
        dir = in.readInt() != 0;
    }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(path);
        out.writeLong(size);
        out.writeLong(mtimeMs);
        out.writeInt(dir ? 1 : 0);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<RemoteStat> CREATOR = new Creator<RemoteStat>() {
        @Override public RemoteStat createFromParcel(Parcel in) { return new RemoteStat(in); }
        @Override public RemoteStat[] newArray(int size) { return new RemoteStat[size]; }
    };
}
