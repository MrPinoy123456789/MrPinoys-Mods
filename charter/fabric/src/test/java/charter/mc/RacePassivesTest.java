package charter.mc;

import java.util.UUID;

import static charter.mc.FabricSuite.check;

/** The Bedrock-only race effects: who counts as Bedrock and how a list entry reads. */
final class RacePassivesTest {

    private RacePassivesTest() {
    }

    static void run() {
        check("a Floodgate uuid is Bedrock", RacePassives.isBedrock(UUID.fromString("00000000-0000-0000-0009-01f99022d2b1")));
        check("a Java uuid is not", !RacePassives.isBedrock(UUID.fromString("ef533120-7a20-4e2e-8072-66decf8bbc0b")));
        var water = RacePassives.parse("water:minecraft:speed#1");
        check("a water entry is water only", water.waterOnly() && water.id().equals("minecraft:speed") && water.amplifier() == 1);
        var plain = RacePassives.parse("minecraft:night_vision");
        check("a plain entry is always on, amplifier 0", !plain.waterOnly() && plain.id().equals("minecraft:night_vision") && plain.amplifier() == 0);
        check("a bad amplifier reads as 0", RacePassives.parse("minecraft:speed#x").amplifier() == 0);
    }
}
