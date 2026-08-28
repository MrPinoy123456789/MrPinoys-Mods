package thingy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Suite-items metadata (docs/SUITE_ITEMS.md) for every item in
 * {@link Definitions}: {@code tags} and {@code rarity}.
 *
 * <p>This is a deliberate sidecar, not a field on {@link Definitions.Def}.
 * Tags and rarity are shop/economy-facing editorial metadata; {@code Def} is
 * gameplay data (base item, name, lore, right-click behaviour). Keeping them
 * apart means a change to what an item costs or how it is categorised never
 * touches the file that decides what it does.
 *
 * <p>Values are unchanged from the hand-authored {@code wondrous} suite_items
 * JSON they replace. {@link SuiteItemsGenerator} is what removes the drift
 * risk (PLAN.md Phase 1, hard constraint 3): the {@code item}, {@code name},
 * and {@code minecraft:custom_data} fields of the generated JSON now come
 * from {@link Definitions#ALL} directly, so only tags and rarity are still
 * hand-maintained, and only here.
 */
final class SuiteMetadata {

    record Entry(List<String> tags, String rarity) {}

    private static final Map<String, Entry> BY_ID = new LinkedHashMap<>();

    static {
        put("big_hole_pick", List.of("tool", "tier3", "one_per_player"), "rare");
        put("big_hole_shovel", List.of("tool", "tier3", "one_per_player"), "rare");
        put("big_lazy_hoe", List.of("tool", "tier2", "one_per_player"), "uncommon");
        put("boomerang_pet_ball", List.of("trinket", "tier2", "one_per_player"), "uncommon");
        put("carry_glove", List.of("trinket", "tier3", "one_per_player"), "rare");
        put("chuck_it_wand", List.of("tool", "tier3", "one_per_player"), "rare");
        put("crafting_station", List.of("tool", "tier2", "one_per_player"), "uncommon");
        put("fishy_necklace", List.of("trinket", "tier2", "one_per_player"), "uncommon");
        put("floaty_feet", List.of("trinket", "tier3", "one_per_player"), "rare");
        put("flying_boots", List.of("trinket", "tier4", "one_per_player"), "legendary");
        put("frog_boots", List.of("trinket", "tier2", "one_per_player"), "uncommon");
        put("growy_can", List.of("tool", "tier2", "one_per_player"), "uncommon");
        put("lazy_sprinkler", List.of("tool", "tier3", "one_per_player"), "rare");
        put("link_wand", List.of("tool", "tier3", "one_per_player"), "rare");
        put("long_arm_gloves", List.of("trinket", "tier2", "one_per_player"), "uncommon");
        put("owl_eye_goggles", List.of("trinket", "tier3", "one_per_player"), "rare");
        put("peek_box", List.of("trinket", "tier3", "one_per_player"), "rare");
        put("pocket_anvil", List.of("tool", "tier2", "one_per_player"), "uncommon");
        put("pocket_disenchanter", List.of("tool", "tier3", "one_per_player"), "rare");
        put("pocket_enderchest", List.of("trinket", "tier3", "one_per_player"), "rare");
        put("pocket_smelter", List.of("tool", "tier3", "one_per_player"), "rare");
        put("pocket_workbench", List.of("tool", "tier2", "one_per_player"), "uncommon");
        put("restock_ring", List.of("trinket", "tier3", "one_per_player"), "rare");
        put("smashy_mortar", List.of("tool", "tier2", "one_per_player"), "uncommon");
        put("sticky_grip_boots", List.of("trinket", "tier3", "one_per_player"), "rare");
        put("tidy_up_stick", List.of("tool", "tier2", "one_per_player"), "uncommon");
        put("toasty_scarf", List.of("trinket", "tier3", "one_per_player"), "rare");
        put("void_bin", List.of("trinket", "tier2", "one_per_player"), "uncommon");
        put("zoomies_boots", List.of("trinket", "tier2", "one_per_player"), "uncommon");
    }

    private static void put(String id, List<String> tags, String rarity) {
        BY_ID.put(id, new Entry(tags, rarity));
    }

    private SuiteMetadata() {}

    /** The tags and rarity for a bare {@link Definitions.Def} id, or {@code null} if none is recorded. */
    static Entry of(String bareId) {
        return BY_ID.get(bareId);
    }
}
