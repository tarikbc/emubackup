package com.tarikbc.emubackup;

/**
 * What to do next during a resumable upload.
 *
 * <p>Extracted as a value type with a pure decision function because the offset arithmetic is
 * where resumable uploads go wrong, and it is worth testing exhaustively without a network. The
 * same discipline as the resumable-download logic in the developer's other app.
 */
public final class UploadDecision {

    public enum Kind {
        /** The server has some of it; continue from {@link #nextOffset}. */
        CONTINUE,
        /** Fully uploaded. */
        DONE,
        /** The session is gone; begin a new one and send from the start. */
        RESTART_SESSION,
        /** Transient. Back off and query the session's status again. */
        RETRY_AFTER_BACKOFF,
        /** Unrecoverable. */
        FAIL
    }

    public final Kind kind;
    public final long nextOffset;
    public final String detail;

    UploadDecision(Kind kind, long nextOffset, String detail) {
        this.kind = kind;
        this.nextOffset = nextOffset;
        this.detail = detail;
    }

    @Override public String toString() {
        return kind + "@" + nextOffset + (detail == null ? "" : " (" + detail + ")");
    }
}
