package com.tarikbc.emubackup;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RemediesTest {

    @Test void duckstationSaysWhatWasSeen() {
        Remedies.Remedy r = Remedies.forTarget("duckstation-memcards", "DuckStation");
        assertFalse(r.fixable());
        assertTrue(r.text.contains("older version"));
    }

    @Test void othersSayWhyNot() {
        Remedies.Remedy r = Remedies.forTarget("amethyst-worlds", "Amethyst");
        assertFalse(r.fixable());
        assertTrue(r.text.startsWith("Amethyst keeps"));
    }
}
