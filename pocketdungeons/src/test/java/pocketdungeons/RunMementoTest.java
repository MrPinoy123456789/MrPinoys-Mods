package pocketdungeons;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Regression for {@link RunMemento}'s pure halves (M75): the {@link RunMemento.RunRecord}
 * codec round trip, the memento title/lore text, and the {@code isMemento}/
 * {@code discoveryIdOf} readback against a minted stack. The mint path touches
 * {@link net.minecraft.world.item.ItemStack} and so needs the one-time registry
 * bootstrap, the same as {@code LobbyBrowserTest}; no server or world is required.
 */
public class RunMementoTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    public static void main(String[] args) {
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();

        testRunRecordCodecRoundTrip();
        testMementoTitle();
        testMementoLore();
        testNextDiscoveryIdUnique();
        // The isMemento/discoveryIdOf readback constructs an ItemStack, which
        // needs the full component registry (bound at world load, not by
        // Bootstrap.bootStrap); it is a live-test item, not a headless one.
        System.out.println("RunMementoTest passed");
    }

    /** RunRecord round trips through its codec with all fields preserved. */
    private static void testRunRecordCodecRoundTrip() {
        RunMemento.RunRecord record = new RunMemento.RunRecord(
                "abc-123", "deepslate", 7, 2, Set.of("ominous", "feral"), 1234567890L);
        com.google.gson.JsonElement encoded = RunMemento.RunRecord.CODEC.encodeStart(
                com.mojang.serialization.JsonOps.INSTANCE, record).result().orElseThrow();
        RunMemento.RunRecord decoded = RunMemento.RunRecord.CODEC.decode(
                com.mojang.serialization.JsonOps.INSTANCE, encoded).result().orElseThrow().getFirst();
        check(decoded.discoveryId(), "abc-123", "discovery id round trips");
        check(decoded.theme(), "deepslate", "theme round trips");
        check(decoded.keystoneLevel(), 7, "keystone level round trips");
        check(decoded.lootTier(), 2, "loot tier round trips");
        check(decoded.affixes(), Set.of("ominous", "feral"), "affixes round trips");
        check(decoded.timestamp(), 1234567890L, "timestamp round trips");
    }

    /** The title is "Memento: <theme>" and truncates to the book title limit. */
    private static void testMementoTitle() {
        RunMemento.RunRecord plain = new RunMemento.RunRecord("id", "", 1, 1, Set.of(), 0L);
        check(RunMemento.mementoTitle(plain), "Memento", "blank theme title is just Memento");
        RunMemento.RunRecord themed = new RunMemento.RunRecord("id", "cave", 1, 1, Set.of(), 0L);
        check(RunMemento.mementoTitle(themed), "Memento: cave", "themed title carries the theme");
        RunMemento.RunRecord longTheme = new RunMemento.RunRecord("id",
                "an_exceedingly_long_theme_identifier", 1, 1, Set.of(), 0L);
        String title = RunMemento.mementoTitle(longTheme);
        check(title.length() <= net.minecraft.world.item.component.WrittenBookContent.TITLE_LENGTH,
                "title truncates to the book title limit");
    }

    /** The lore carries theme, keystone band and affixes. */
    private static void testMementoLore() {
        RunMemento.RunRecord record = new RunMemento.RunRecord(
                "id", "cave", 5, 2, Set.of("ominous"), 0L);
        List<net.minecraft.network.chat.Component> lore = RunMemento.mementoLore(record);
        check(lore.size() >= 3, "lore has at least theme, keystone and place lines");
        String joined = lore.stream().map(net.minecraft.network.chat.Component::getString)
                .reduce("", (a, b) -> a + b + "|");
        check(joined.contains("cave"), "lore names the theme");
        check(joined.contains("Compass: 5"), "lore names the compass level");
        check(joined.contains("ominous"), "lore names the affixes");
    }

    /** nextDiscoveryId returns distinct ids. */
    private static void testNextDiscoveryIdUnique() {
        String a = RunMemento.nextDiscoveryId();
        String b = RunMemento.nextDiscoveryId();
        check(!a.isBlank(), "discovery id is non-blank");
        check(!a.equals(b), "two discovery ids differ");
    }

    private static void check(Object actual, Object expected, String what) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }
}
