package charter.gametest;

import charter.mc.Text;
import charter.mc.book.NewcomerPages;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

/** The newcomer's first ten minutes: the join title, the chooser label, and the words that point at the lectern. */
public final class NewcomerFirstMinutesGameTest {

    @GameTest(maxTicks = 100)
    public void aKingdomlessJoinGetsTheTitleOncePerSession(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer drifter = kit.outsider();
            // The mock player's own login already fired the join hook, which titled it; start a fresh session.
            NewcomerPages.forgetTitle(drifter.getUUID());
            helper.assertTrue(NewcomerPages.welcomeTitle(drifter), "A player with no kingdom got no join title");
            helper.assertTrue(!NewcomerPages.welcomeTitle(drifter), "The join title was shown twice in one session");
            NewcomerPages.forgetTitle(drifter.getUUID());
            helper.assertTrue(NewcomerPages.welcomeTitle(drifter), "The join title did not return after a new login");
            NewcomerPages.forgetTitle(drifter.getUUID());
            helper.assertTrue(Text.get("join.title").equals("Kingdoms of Fantasy"), "Title text: " + Text.get("join.title"));
            helper.assertTrue(Text.get("join.subtitle").equals("Pick your kingdom at the sparkling lectern"),
                    "Subtitle text: " + Text.get("join.subtitle"));
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100)
    public void aMemberJoinGetsNoTitle(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Ttl");
            helper.assertTrue(!NewcomerPages.welcomeTitle(lord), "A kingdom member was given the newcomer title");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100)
    public void theChooserLabelFitsAPhoneButton(GameTestHelper helper) {
        helper.assertTrue(NewcomerPages.chooserLabel("Azrak", "Dwarf", 5).equals("Azrak, the Dwarf (5)"),
                "Label: " + NewcomerPages.chooserLabel("Azrak", "Dwarf", 5));
        for (String[] k : new String[][]{{"Faeloria", "Fae"}, {"Azurius", "Merfolk"}, {"Everdelle", "Fairy"}, {"A very long kingdom", "Elderfolkish"}}) {
            String label = NewcomerPages.chooserLabel(k[0], k[1], 85);
            helper.assertTrue(label.length() <= 24, "Too long (" + label.length() + "): " + label);
            helper.assertTrue(label.endsWith("(85)"), "Count missing: " + label);
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 100)
    public void theWordsPointAtTheLectern(GameTestHelper helper) {
        helper.assertTrue(Text.get("land.drifter").contains("sparkling lectern"), "land.drifter: " + Text.get("land.drifter"));
        helper.assertTrue(Text.get("pad.join_hint").contains("sparkling lectern"), "pad.join_hint: " + Text.get("pad.join_hint"));
        for (String race : new String[]{"dwarf", "human", "merfolk", "fae", "fairy"}) {
            helper.assertTrue(Text.has("race.traits." + race), "No trait line for " + race);
        }
        helper.assertTrue(NewcomerPages.raceSlug("Dwarf").equals("dwarf"), "slug");
        helper.succeed();
    }

    @GameTest(maxTicks = 100)
    public void theKingdomPageNamesItsLord(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Lrd");
            var names = NewcomerPages.lordNames(kit.rt, kit.orgOf(lord));
            helper.assertTrue(names.size() == 1, "Lord names: " + names);
            String asked = NewcomerPages.askedText(kit.rt, kit.orgOf(lord));
            helper.assertTrue(asked.contains(names.get(0)), "The asked text does not name the Lord: " + asked);
            helper.succeed();
        }
    }
}
