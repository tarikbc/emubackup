package com.tarikbc.emubackup;

/**
 * The flavour of identifier a grouping rule captures, which decides how it is normalised
 * and which name database can resolve it.
 */
public enum IdKind {
    /** 16 hex chars, e.g. {@code 0100152000022000}. */
    SWITCH_TITLE_ID,
    /** 32 hex chars naming an Eden profile directory, e.g. {@code F255133E7DABC494CD4B3089D53DB2DB}. */
    SWITCH_USER_UUID,
    /** Four letters plus five digits, e.g. {@code ULUS10336}. Trailing suffixes are trimmed. */
    PSP_GAME_ID,
    /** e.g. {@code SLUS-00594}. */
    PSX_SERIAL,
    /** e.g. {@code SLUS-20946}. */
    PS2_SERIAL,
    /** Six chars, e.g. {@code GALE01}. */
    GC_GAME_ID,
    /** 3DS title id, usually split across two path components and rejoined. */
    N3DS_TITLE_ID,
    /** The ROM filename with extension and bracket tags stripped. */
    ROM_BASENAME
}
