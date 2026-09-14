package com.tarikbc.emubackup;

/**
 * One line in {@link TargetsActivity}: either an emulator heading or a target.
 *
 * <p>A flat list of mixed rows rather than a nested structure, because the list is short, the
 * grouping is purely visual, and a sectioned adapter would be more machinery than the screen
 * earns.
 */
public final class TargetRow {

    public final boolean header;
    public final String headerText;
    public final Target target;
    public final TargetScan scan;
    public final String emulatorLabel;

    private TargetRow(boolean header, String headerText, Target target, TargetScan scan, String emulatorLabel) {
        this.header = header;
        this.headerText = headerText;
        this.target = target;
        this.scan = scan;
        this.emulatorLabel = emulatorLabel;
    }

    public static TargetRow header(String text) {
        return new TargetRow(true, text, null, null, null);
    }

    public static TargetRow of(Target t, TargetScan s, String emulatorLabel) {
        return new TargetRow(false, null, t, s, emulatorLabel);
    }
}
