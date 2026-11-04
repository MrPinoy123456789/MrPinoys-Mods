package pocketdungeons;

import java.util.List;

/**
 * Who cleared a wild dungeon (decision 2026-11-04): the label read from Charter's tag, the side with the most players
 * inside, and the banner dye nearest to a kingdom's colour.
 */
public class WildKingdomsTest {

    public static void main(String[] args) {
        check("Aurelia".equals(WildKingdoms.labelOf("[Aurelia] ")), "a plain tag");
        check("Faeloria".equals(WildKingdoms.labelOf("[Faeloria] Queen ")), "a tag with a rank");
        check("Moss".equals(WildKingdoms.labelOf("[Moss] ")), "a short label");
        check(WildKingdoms.labelOf("Steve") == null && WildKingdoms.labelOf("[] ") == null
                && WildKingdoms.labelOf(null) == null, "no tag, no label");
        check("Mercenaries".equals(WildKingdoms.plural("Mercenary")) && "Azrak".equals(WildKingdoms.plural("Azrak")),
                "mercenaries read as a group");

        WildKingdoms.Side aurelia = new WildKingdoms.Side("Aurelia", 0xFF6A1A);
        WildKingdoms.Side azrak = new WildKingdoms.Side("Azrak", 0xFFD700);
        WildKingdoms.Side steve = new WildKingdoms.Side("Steve", -1);
        check(WildKingdoms.majority(List.of(azrak, aurelia, aurelia)).equals(aurelia), "the bigger group wins");
        check(WildKingdoms.majority(List.of(azrak, aurelia)).equals(azrak), "a tie goes to the first listed");
        check(WildKingdoms.majority(List.of(steve, aurelia)).equals(aurelia), "a kingdom beats a lone name on a tie");
        check(WildKingdoms.majority(List.of(steve)).equals(steve), "a lone player is named");
        check(WildKingdoms.majority(List.of()) == null, "nobody inside");

        check(WildBanner.nearestDye(0xFF6A1A) == 1, "Aurelia's red orange is an orange banner");
        check(WildBanner.nearestDye(0xFFD700) == 4, "Azrak's gold is a yellow banner");
        check(WildBanner.nearestDye(0x388E3C) == 13 || WildBanner.nearestDye(0x388E3C) == 5, "Moss green is green");
        check(WildBanner.nearestDye(0xFFFFFF) == 0 && WildBanner.nearestDye(0x000000) == 15, "white and black");
        System.out.println("WildKingdomsTest passed");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
