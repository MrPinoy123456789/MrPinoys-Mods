package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.ChatFormatting;
import net.minecraft.gametest.framework.GameTestHelper;

import java.util.List;

/**
 * The floor history board (playtest 2026-10-02-1): progress notation and its
 * colours, fixed-width cells so the columns line up, date only.
 */
public final class FloorHistoryGameTest {

    @GameTest
    public void progressReadsAsFractionsWithTheAgreedColours(GameTestHelper helper) {
        helper.assertValueEqual(FloorHistory.progress(1, 3), "1/3", "first floor");
        helper.assertValueEqual(FloorHistory.progress(4, 3), "4/3", "past the interval");
        helper.assertTrue(FloorHistory.progressColour(1, 3) == ChatFormatting.YELLOW, "1/3 is yellow");
        helper.assertTrue(FloorHistory.progressColour(2, 3) == ChatFormatting.YELLOW, "2/3 is yellow");
        helper.assertTrue(FloorHistory.progressColour(3, 3) == ChatFormatting.GREEN, "3/3 is green");
        helper.assertTrue(FloorHistory.progressColour(4, 3) == ChatFormatting.GOLD, "4/3 is orange");
        helper.assertTrue(FloorHistory.progressColour(7, 3) == ChatFormatting.GOLD, "7/3 is orange");
        helper.succeed();
    }

    @GameTest
    public void everyRowHasTheSameWidthSoColumnsAlign(GameTestHelper helper) {
        long now = System.currentTimeMillis();
        List<FloorHistory.Entry> entries = List.of(
                new FloorHistory.Entry(now, 3, "pocketdungeons:basalt_foundry", 1,
                        List.of(AffixIds.OVERCLOCKED), 425, FloorHistory.CLEARED, "", ""),
                new FloorHistory.Entry(now, 12, "pocketdungeons:crypt", 3, List.of(),
                        5, FloorHistory.FAILED, "pocketdungeons:a_very_long_room_name_indeed", "in_fire"),
                new FloorHistory.Entry(now, 1, "", 4, List.of(AffixIds.OMINOUS, AffixIds.FERAL, AffixIds.SILENCED),
                        6000, FloorHistory.QUIT, "pocketdungeons:thicket", ""));
        int expected = -1;
        for (FloorHistory.Entry entry : entries) {
            int width = FloorHistory.line(entry).getString().length();
            if (expected < 0) {
                expected = width;
            }
            helper.assertValueEqual(width, expected, "row width for " + entry.outcome());
        }
        helper.assertTrue(!FloorHistory.line(entries.get(0)).getString().substring(0, FloorHistory.COLUMNS[0]).contains(":"),
                "the date carries no time of day");
        helper.succeed();
    }

    @GameTest
    public void affixShortNamesAreContractionsOfAtMostFiveLetters(GameTestHelper helper) {
        for (AffixDefinition def : AffixManifest.current().definitions()) {
            helper.assertTrue(def.shortName.length() <= 5, def.id + " short name too long: " + def.shortName);
            if (def.label.length() <= 5) {
                helper.assertTrue(def.shortName.equals(def.label), def.id + " is already short and keeps its name");
            }
        }
        helper.succeed();
    }
}
