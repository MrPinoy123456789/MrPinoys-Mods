package pocketdungeons;

import java.util.ArrayList;
import java.util.List;

/**
 * More gold ore in wild dungeons (owner, 2026-11-04: gold is the kingdom server's currency and its sink, and it has
 * too few sources). Every room of a wild floor buries {@link #EXTRA_POCKETS} more hidden ore pockets than its dungeon
 * declares, and about half of all its pockets are gold. The gold matches the dungeon's stone: nether gold ore where
 * the dungeon is nether rock, deepslate gold ore where it is deepslate (judged from its own hidden ore, else its node
 * palette), else plain gold ore. A dungeon with no hidden
 * ore of its own (the End, the deep quarry) gets gold pockets only. Campaign floors are untouched: only the wild stamp
 * calls this. Pure, so {@code WildGoldTest} runs without a world.
 */
final class WildGold {

    // Hardcoded for now: switch these to pocketdungeons.json knobs (tools/new_knob.py --regen) when available.
    /** Pockets added to each room's range, on top of what the dungeon declares (capped by HiddenOre.MAX_POCKETS). */
    static final int EXTRA_POCKETS = 2;
    /** Pocket size range for a dungeon that declares no hidden ore of its own. */
    static final int SIZE_MIN = 2;
    static final int SIZE_MAX = 4;

    static final String GOLD = "minecraft:gold_ore";
    static final String DEEPSLATE_GOLD = "minecraft:deepslate_gold_ore";
    static final String NETHER_GOLD = "minecraft:nether_gold_ore";

    private WildGold() {}

    /** The same stamp context with the wild gold added to its hidden ore. */
    static NodeStamper.Context enrich(NodeStamper.Context ctx) {
        // The dungeon's own hidden ore says best what its rock is; its node palette when it buries none.
        String gold = goldFor(ctx.hiddenOre() != null && !ctx.hiddenBlocks().isEmpty()
                ? ctx.hiddenBlocks() : List.copyOf(ctx.palette()));
        DungeonDef.HiddenOre hidden = ctx.hiddenOre();
        List<String> blocks = goldMix(hidden == null ? List.of() : ctx.hiddenBlocks(), gold);
        DungeonDef.HiddenOre richer;
        if (hidden == null) {
            richer = new DungeonDef.HiddenOre(1, Math.min(DungeonDef.HiddenOre.MAX_POCKETS, 1 + EXTRA_POCKETS),
                    SIZE_MIN, SIZE_MAX, blocks);
        } else {
            int max = Math.min(DungeonDef.HiddenOre.MAX_POCKETS, hidden.pocketsMax() + EXTRA_POCKETS);
            int min = Math.min(max, hidden.pocketsMin() + 1);
            richer = new DungeonDef.HiddenOre(min, max, hidden.sizeMin(), hidden.sizeMax(), blocks,
                    hidden.deepBlocks(), hidden.deepEvery());
        }
        return new NodeStamper.Context(ctx.palette(), ctx.nodeLight(), richer, blocks);
    }

    /**
     * The hidden ore mix with gold making up about half of it: as many gold entries as there are other entries (a
     * block listed twice is twice as likely), and gold alone when there are none.
     */
    static List<String> goldMix(List<String> blocks, String gold) {
        List<String> out = new ArrayList<>(blocks);
        int add = Math.max(1, blocks.size());
        for (int i = 0; i < add; i++) {
            out.add(gold);
        }
        return List.copyOf(out);
    }

    /** Which gold ore suits a dungeon built of these blocks. */
    static String goldFor(List<String> stone) {
        boolean deepslate = false;
        for (String block : stone) {
            String path = block.contains(":") ? block.substring(block.indexOf(':') + 1) : block;
            if (path.startsWith("nether_") || path.contains("blackstone")) {
                return NETHER_GOLD;
            }
            if (path.startsWith("deepslate")) {
                deepslate = true;
            }
        }
        return deepslate ? DEEPSLATE_GOLD : GOLD;
    }
}
