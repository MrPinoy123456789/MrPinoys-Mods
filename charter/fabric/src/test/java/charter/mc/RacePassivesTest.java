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
        check("a water entry is water only", water.waterOnly() && !water.swimOnly() && water.id().equals("minecraft:speed") && water.amplifier() == 1);
        var swim = RacePassives.parse("swim:minecraft:dolphins_grace");
        check("a swim entry is swim only", swim.swimOnly() && !swim.waterOnly() && swim.id().equals("minecraft:dolphins_grace") && swim.amplifier() == 0);
        var under = RacePassives.parse("under:minecraft:night_vision");
        check("an under entry is head under water only", under.underOnly() && under.conditional() && under.id().equals("minecraft:night_vision"));
        var plain = RacePassives.parse("minecraft:night_vision");
        check("a plain entry is not conditional", !plain.conditional());
        check("a plain entry is always on, amplifier 0", !plain.waterOnly() && !plain.swimOnly() && plain.id().equals("minecraft:night_vision") && plain.amplifier() == 0);
        check("a bad amplifier reads as 0", RacePassives.parse("minecraft:speed#x").amplifier() == 0);
        check("a passive never reaches the night vision flicker between refreshes",
                RacePassives.LASTS_TICKS - RacePassives.EVERY_TICKS > RacePassives.FLICKER_TICKS);
    }
}
