package com.tarikbc.emubackup;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Two jobs.
 *
 * <p>The nested {@code ShippedRegistry} class validates the real
 * {@code src/main/assets/targets.json} that goes into the APK. That is the point of keeping
 * the registry as data: these assertions run in CI, so a bad glob or a save-state target left
 * enabled by default fails the build instead of quietly changing what gets backed up.
 *
 * <p>The nested {@code Parser} class checks that the parser rejects what it should.
 */
class TargetRegistryTest {

    private static String shippedJson() {
        // Tests run from the repo root, the same place ./test.sh is invoked.
        Path p = Paths.get("src/main/assets/targets.json");
        assertTrue(Files.exists(p), "shipped registry not found at " + p.toAbsolutePath());
        try {
            return new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError("could not read " + p, e);
        }
    }

    @Nested
    class ShippedRegistry {

        private final TargetRegistry reg = TargetRegistry.parse(shippedJson());

        @Test
        @DisplayName("every emulator found in the device inventory is covered")
        void coversTheInventory() {
            Set<String> want = new HashSet<>(Arrays.asList(
                    "eden", "ps2", "ppsspp", "azahar", "citra-legacy", "dolphin", "duckstation",
                    "melonds", "retroarch", "vita3k", "aps3e", "rpcsx", "gtasa",
                    "minecraft-bedrock", "amethyst", "clonehero"));
            Set<String> have = new HashSet<>();
            for (Emulator e : reg.emulators()) have.add(e.id);
            want.removeAll(have);
            assertTrue(want.isEmpty(), "registry is missing emulators from the inventory: " + want);
        }

        @Test void parsesWithoutWarnings() {
            assertTrue(reg.warnings().isEmpty(), "unexpected warnings: " + reg.warnings());
        }

        @Test
        @DisplayName("save states are never enabled by default")
        void statesAreOptIn() {
            // States are ~480 MB of the ~730 MB inventory and are disposable snapshots tied
            // to one emulator build. Shipping them on would quietly triple every backup.
            for (Target t : reg.allTargets()) {
                if (t.category == Category.STATE) {
                    assertFalse(t.enabledByDefault, t.id + " is a STATE target but is enabled by default");
                    assertTrue(t.storeOnly(), t.id + " is a STATE target and should use compress:store");
                }
            }
        }

        @Test
        @DisplayName("console keys are opt-in and marked sensitive")
        void keysAreOptInAndFlagged() {
            List<String> keyTargets = new ArrayList<>();
            for (Target t : reg.allTargets()) {
                if (t.category == Category.KEY) {
                    keyTargets.add(t.id);
                    assertFalse(t.enabledByDefault, t.id + " is a KEY target but is enabled by default");
                    assertTrue(t.sensitive, t.id + " is a KEY target but is not marked sensitive");
                }
            }
            assertTrue(keyTargets.contains("eden-keys"), "expected an eden-keys target");
            assertTrue(keyTargets.contains("citra-keys"), "expected a citra-keys target");
        }

        @Test
        @DisplayName("the RetroArch VMU target reaches the saves and nothing else in a 4 GB BIOS folder")
        void retroarchVmuIsNarrow() {
            // The real device keeps these saves in a dc/ subdirectory alongside more BIOS, so
            // the target has to recurse. The guarantee is therefore not "shallow" but "matches
            // only save filenames" — asserted behaviourally against the real names observed on
            // the device, because that is the property that actually protects the user.
            PathMatcher m = reg.target("retroarch-vmu").matcher();
            assertTrue(m.matches("dc/vmu_save_A1.bin"), "must reach the saves in the dc/ subdirectory");
            assertTrue(m.matches("dc/dc_nvmem.bin"));
            for (String bios : new String[]{
                    "dc/dc_boot.bin", "dc/naomi.zip", "dc/naomi2.zip", "dc/boot.bin", "dc/flash.bin",
                    "dc/f355bios.zip", "dc/segasp.zip", "scph5500.bin", "32X_G_BIOS.BIN",
                    "3dobios.zip", "bios_CD_E.bin", "BIOS.col"}) {
                assertFalse(m.matches(bios), "a BIOS image is reachable by the VMU target: " + bios);
            }
        }

        @Test
        @DisplayName("the melonDS target cannot reach the ROMs beside the saves")
        void melondsIsNarrow() {
            Target t = reg.target("melonds-saves");
            assertTrue(t.matcher().matches("Pokemon.sav"));
            assertFalse(t.matcher().matches("Pokemon.zip"));
            assertFalse(t.matcher().matches("Mario Kart DS.nds"));
        }

        @Test
        @DisplayName("the two 3DS save sets stay separate")
        void threeDsSetsAreDisjoint() {
            // Azahar in shared storage and the legacy Citra install in app-private storage
            // share nothing. Merging them would restore one emulator's saves into the other.
            assertEquals(Tier.SHARED, reg.target("azahar-sdmc").tier);
            assertEquals(Tier.APP_PRIVATE, reg.target("citra-sdmc").tier);
            assertNotEquals(reg.emulatorOf("azahar-sdmc").id, reg.emulatorOf("citra-sdmc").id);
        }

        @Test
        @DisplayName("the emulator's own rolling backup folder is opt-in")
        void ps2MemcardBackupsAreOptIn() {
            assertFalse(reg.target("ps2-memcard-backups").enabledByDefault);
        }

        @Test
        @DisplayName("the .bak rollbacks the PS2 emulator writes beside each card are covered")
        void ps2MemcardRollbacksAreCovered() {
            // Observed on device: mcd001.ps2.bak and mcd001.ps2.bak2 sit in the memcards folder
            // itself, not in memcard-backups. A *.ps2 glob alone silently misses them.
            PathMatcher m = reg.target("ps2-memcards").matcher();
            assertTrue(m.matches("mcd001.ps2"));
            assertTrue(m.matches("mcd001.mcr"));
            assertTrue(m.matches("mcd001.ps2.bak"));
            assertTrue(m.matches("mcd001.ps2.bak2"));
        }

        @Test
        @DisplayName("Eden's saves are coupled to its profiles")
        void edenSavesRequireProfiles() {
            // A Switch save is keyed to a profile UUID. Restoring saves without the profile
            // file leaves them orphaned, which is exactly the failure this app was built for.
            assertTrue(reg.target("eden-saves").coupledWith.contains("eden-profiles"));
            assertTrue(reg.target("eden-profiles").critical);
            assertEquals(new HashSet<>(Arrays.asList("eden-saves", "eden-profiles")),
                    reg.withCoupled(new HashSet<>(Arrays.asList("eden-saves"))));
        }

        @Test
        @DisplayName("every app-private target names the package its path depends on")
        void appPrivateTargetsNamePackages() {
            PathResolver r = new PathResolver("/storage/emulated/0");
            for (Target t : reg.allTargets()) {
                if (t.tier == Tier.APP_PRIVATE) {
                    assertNotNull(t.pkg, t.id + " is APP_PRIVATE but has no pkg");
                    assertTrue(reg.emulatorOf(t.id).packages.contains(t.pkg),
                            t.id + " names pkg " + t.pkg + " which is not in its emulator's package list");
                }
                assertDoesNotThrow(() -> r.resolve(t.root, t.pkg), t.id + " has an unresolvable root");
            }
        }

        @Test
        @DisplayName("every target has a positive cap and a non-empty include list")
        void everyTargetIsBounded() {
            for (Target t : reg.allTargets()) {
                assertTrue(t.maxBytes > 0, t.id + " has no maxBytes");
                assertFalse(t.include.isEmpty(), t.id + " has an empty include list");
            }
        }

        @Test
        @DisplayName("grouping rules that claim a profile axis actually capture one")
        void groupingIsCoherent() {
            for (Target t : reg.allTargets()) {
                Grouping g = t.grouping;
                if (g == null) continue;
                assertNotNull(g.gameIdKind, t.id + " grouping has no gameIdKind");
                if (g.hasProfileAxis()) {
                    assertNotNull(g.pattern, t.id + " has a profile axis but no path pattern");
                    assertTrue(g.pattern.captureNames().contains("profile"));
                }
            }
        }

        @Test
        @DisplayName("the default selection is saves only")
        void defaultsAreSavesOnly() {
            for (Target t : reg.defaultEnabledTargets()) {
                assertEquals(Category.SAVE, t.category,
                        t.id + " is enabled by default but is a " + t.category + " target");
            }
            assertTrue(reg.defaultEnabledTargets().size() >= 15,
                    "expected most SAVE targets on by default, got " + reg.defaultEnabledTargets().size());
        }
    }

    @Nested
    class Parser {

        /** A minimal valid registry with one target, patched per test. */
        private String registry(String targetBody) {
            return "{\"registryVersion\":1,\"emulators\":[{"
                    + "\"id\":\"e\",\"label\":\"E\",\"packages\":[\"a.b.c\"],"
                    + "\"targets\":[" + targetBody + "]}]}";
        }

        private String target(String extra) {
            return "{\"id\":\"t\",\"label\":\"T\",\"category\":\"SAVE\",\"tier\":\"SHARED\","
                    + "\"root\":\"{EXT}/x\",\"include\":[\"**\"],\"maxBytes\":1024" + extra + "}";
        }

        @Test void minimalRegistryParses() {
            TargetRegistry r = TargetRegistry.parse(registry(target("")));
            assertEquals(1, r.allTargets().size());
            assertTrue(r.target("t").enabledByDefault);
            assertTrue(r.target("t").recursive);
            assertEquals("deflate", r.target("t").compress);
        }

        @Test void rejectsDuplicateTargetIds() {
            String two = target("") + "," + target("");
            assertThrows(TargetRegistry.RegistryException.class, () -> TargetRegistry.parse(registry(two)));
        }

        @Test void rejectsStateEnabledByDefault() {
            assertThrows(TargetRegistry.RegistryException.class,
                    () -> TargetRegistry.parse(registry(target(",\"category\":\"STATE\""))));
        }

        @Test void rejectsKeyTargetThatIsNotSensitive() {
            assertThrows(TargetRegistry.RegistryException.class, () -> TargetRegistry.parse(
                    registry(target(",\"category\":\"KEY\",\"enabledByDefault\":false"))));
        }

        @Test void rejectsDataRootWithoutPackage() {
            assertThrows(TargetRegistry.RegistryException.class, () -> TargetRegistry.parse(
                    registry("{\"id\":\"t\",\"label\":\"T\",\"category\":\"SAVE\",\"tier\":\"APP_PRIVATE\","
                            + "\"root\":\"{DATA}/f\",\"include\":[\"**\"],\"maxBytes\":1024}")));
        }

        @Test
        @DisplayName("root form and tier must agree")
        void rejectsTierMismatch() {
            assertThrows(TargetRegistry.RegistryException.class,
                    () -> TargetRegistry.parse(registry(target(",\"tier\":\"APP_PRIVATE\",\"pkg\":\"a.b.c\""))));
        }

        @Test void rejectsEmptyInclude() {
            assertThrows(TargetRegistry.RegistryException.class, () -> TargetRegistry.parse(
                    registry("{\"id\":\"t\",\"label\":\"T\",\"category\":\"SAVE\",\"tier\":\"SHARED\","
                            + "\"root\":\"{EXT}/x\",\"include\":[],\"maxBytes\":1024}")));
        }

        @Test
        @DisplayName("a ** glob on a non-recursive target can never match, so it is rejected")
        void rejectsUnmatchableGlob() {
            assertThrows(TargetRegistry.RegistryException.class,
                    () -> TargetRegistry.parse(registry(target(",\"recursive\":false"))));
        }

        @Test void rejectsUnknownEnumValue() {
            assertThrows(TargetRegistry.RegistryException.class,
                    () -> TargetRegistry.parse(registry(target(",\"category\":\"SCREENSHOT\""))));
        }

        @Test void rejectsCoupledWithDanglingReference() {
            assertThrows(TargetRegistry.RegistryException.class,
                    () -> TargetRegistry.parse(registry(target(",\"coupledWith\":[\"nope\"]"))));
        }

        @Test void rejectsNegativeMaxBytes() {
            assertThrows(TargetRegistry.RegistryException.class, () -> TargetRegistry.parse(
                    registry("{\"id\":\"t\",\"label\":\"T\",\"category\":\"SAVE\",\"tier\":\"SHARED\","
                            + "\"root\":\"{EXT}/x\",\"include\":[\"**\"],\"maxBytes\":0}")));
        }

        @Test void rejectsNewerRegistryVersion() {
            assertThrows(TargetRegistry.RegistryException.class,
                    () -> TargetRegistry.parse("{\"registryVersion\":99,\"emulators\":[]}"));
        }

        @Test
        @DisplayName("two emulators must not cover overlapping roots")
        void rejectsCrossEmulatorRootOverlap() {
            String json = "{\"registryVersion\":1,\"emulators\":["
                    + "{\"id\":\"a\",\"label\":\"A\",\"packages\":[\"p\"],\"targets\":[" + target("") + "]},"
                    + "{\"id\":\"b\",\"label\":\"B\",\"packages\":[\"q\"],\"targets\":["
                    + "{\"id\":\"u\",\"label\":\"U\",\"category\":\"SAVE\",\"tier\":\"SHARED\","
                    + "\"root\":\"{EXT}/x/deeper\",\"include\":[\"**\"],\"maxBytes\":1024}]}]}";
            assertThrows(TargetRegistry.RegistryException.class, () -> TargetRegistry.parse(json));
        }

        @Test
        @DisplayName("an unknown key is a warning, not a failure, so a newer registry still loads")
        void unknownKeysWarnButLoad() {
            TargetRegistry r = TargetRegistry.parse(registry(target(",\"futureField\":true")));
            assertEquals(1, r.allTargets().size());
            assertEquals(1, r.warnings().size());
            assertTrue(r.warnings().get(0).contains("futureField"));
        }

        @Test void rejectsMalformedJson() {
            assertThrows(TargetRegistry.RegistryException.class, () -> TargetRegistry.parse("{nope"));
            assertThrows(TargetRegistry.RegistryException.class, () -> TargetRegistry.parse(""));
        }

        @Test
        @DisplayName("an override replaces a bundled emulator wholesale")
        void overrideReplacesEmulator() {
            String override = "{\"registryVersion\":1,\"emulators\":[{"
                    + "\"id\":\"e\",\"label\":\"Patched\",\"packages\":[\"a.b.c\"],\"targets\":["
                    + "{\"id\":\"t2\",\"label\":\"T2\",\"category\":\"SAVE\",\"tier\":\"SHARED\","
                    + "\"root\":\"{EXT}/moved\",\"include\":[\"**\"],\"maxBytes\":1024}]}]}";
            TargetRegistry r = TargetRegistry.parseWithOverride(registry(target("")), override);
            assertFalse(r.hasTarget("t"), "the bundled target should have been replaced");
            assertTrue(r.hasTarget("t2"));
            assertEquals("Patched", r.emulator("e").label);
            assertTrue(r.warnings().toString().contains("replaces bundled emulator 'e'"));
        }

        @Test
        @DisplayName("a broken override fails loudly rather than being ignored")
        void brokenOverrideThrows() {
            assertThrows(TargetRegistry.RegistryException.class,
                    () -> TargetRegistry.parseWithOverride(registry(target("")), "{not json"));
        }
    }
}
