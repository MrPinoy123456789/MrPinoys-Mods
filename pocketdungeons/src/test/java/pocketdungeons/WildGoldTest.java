package pocketdungeons;

import java.util.List;
import java.util.Set;

/**
 * More gold ore in wild dungeons (owner, 2026-11-04): {@link WildGold} adds pockets to every room, makes about half
 * of them gold, and picks the gold that suits the dungeon's rock.
 */
public class WildGoldTest {

    public static void main(String[] args) {
        check(WildGold.GOLD.equals(WildGold.goldFor(List.of("coal_ore", "iron_ore", "gold_ore", "sand"))),
                "a stone mine gets plain gold ore");
        check(WildGold.DEEPSLATE_GOLD.equals(WildGold.goldFor(List.of("deepslate_iron_ore", "deepslate_lapis_ore"))),
                "a deepslate dungeon gets deepslate gold ore");
        check(WildGold.NETHER_GOLD.equals(WildGold.goldFor(List.of("nether_gold_ore", "nether_quartz_ore"))),
                "a nether dungeon gets nether gold ore");
        check(WildGold.DEEPSLATE_GOLD.equals(WildGold.goldFor(List.of("minecraft:deepslate_diamond_ore",
                "minecraft:ancient_debris"))), "the End's palette (deepslate diamond, debris) gets deepslate gold");

        List<String> mix = WildGold.goldMix(List.of("coal_ore", "coal_ore", "iron_ore"), WildGold.GOLD);
        long gold = mix.stream().filter(WildGold.GOLD::equals).count();
        check(mix.size() == 6 && gold == 3, "half the mix is gold: " + mix);
        check(WildGold.goldMix(List.of(), WildGold.GOLD).equals(List.of(WildGold.GOLD)), "no ore of its own: gold only");

        DungeonDef.HiddenOre declared = new DungeonDef.HiddenOre(1, 2, 1, 3, List.of("coal_ore", "iron_ore"));
        NodeStamper.Context mine = new NodeStamper.Context(Set.of("coal_ore", "iron_ore"), DungeonRoomMeta.LIGHT_LIT,
                declared, List.of("coal_ore", "iron_ore"));
        NodeStamper.Context richer = WildGold.enrich(mine);
        check(richer.hiddenOre().pocketsMin() == 2 && richer.hiddenOre().pocketsMax() == 2 + WildGold.EXTRA_POCKETS,
                "every room buries more pockets: " + richer.hiddenOre());
        check(richer.hiddenBlocks().contains(WildGold.GOLD) && richer.hiddenBlocks().containsAll(List.of("coal_ore",
                "iron_ore")), "the mine keeps its own ore and gains gold: " + richer.hiddenBlocks());
        check(richer.palette().equals(mine.palette()) && richer.nodeLight().equals(mine.nodeLight()),
                "palette and light are unchanged");

        DungeonDef.HiddenOre full = new DungeonDef.HiddenOre(5, 6, 1, 3, List.of("coal_ore"));
        NodeStamper.Context capped = WildGold.enrich(new NodeStamper.Context(Set.of(), DungeonRoomMeta.LIGHT_LIT,
                full, List.of("coal_ore")));
        check(capped.hiddenOre().pocketsMax() == DungeonDef.HiddenOre.MAX_POCKETS
                && capped.hiddenOre().pocketsMin() <= capped.hiddenOre().pocketsMax(), "pockets stay within the cap");

        NodeStamper.Context bare = WildGold.enrich(new NodeStamper.Context(Set.of("deepslate_diamond_ore"),
                DungeonRoomMeta.LIGHT_LIT));
        check(bare.hiddenOre() != null && bare.hiddenBlocks().equals(List.of(WildGold.DEEPSLATE_GOLD))
                && bare.hiddenOre().problems(List.of()).isEmpty(), "a dungeon with no hidden ore gets gold pockets");
        System.out.println("WildGoldTest passed");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
