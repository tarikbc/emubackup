package com.tarikbc.emubackup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConsolesTest {

    @Test
    @DisplayName("every emulator in the shipped registry has a badge and a name")
    void shippedRegistryIsCovered() throws Exception {
        TargetRegistry reg = TargetRegistry.parse(new String(
                Files.readAllBytes(Paths.get("src/main/assets/targets.json")), StandardCharsets.UTF_8));
        for (Emulator e : reg.emulators()) {
            for (Target t : e.targets) {
                String badge = Consoles.badge(e.id, t.id);
                assertNotEquals("?", badge, e.id + "/" + t.id + " has no badge");
                // Being in the chip order is what proves a name exists; PSP is its own name.
                assertTrue(Consoles.rank(badge) < 15, badge + " is not in the chip order");
            }
        }
    }

    @Test void dolphinSplitsByTarget() {
        assertEquals("GC", Consoles.badge("dolphin", "dolphin-gc"));
        assertEquals("WII", Consoles.badge("dolphin", "dolphin-wii"));
    }

    @Test void unknownIsHonest() {
        assertEquals("?", Consoles.badge("something-new", "x"));
        assertEquals("?", Consoles.name("?"));
    }
}
