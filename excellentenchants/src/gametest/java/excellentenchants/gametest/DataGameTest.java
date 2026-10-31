package excellentenchants.gametest;

import excellentenchants.Enchants;
import excellentenchants.Numbers;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.enchantment.Enchantment;

/** The plugin's numbers, and that every enchantment loaded from the data pack with the plugin's level and weight. */
public final class DataGameTest {

    /** id, max level, weight (Common 10, Uncommon 5, Rare 2, Very Rare 1). */
    private static final Map<ResourceKey<Enchantment>, int[]> EXPECTED = Map.ofEntries(
            Map.entry(Enchants.DRAGON_HEART, new int[]{5, 2}),
            Map.entry(Enchants.FLAME_WALKER, new int[]{2, 1}),
            Map.entry(Enchants.ENDER_BOW, new int[]{1, 1}),
            Map.entry(Enchants.SILK_CHEST, new int[]{1, 1}),
            Map.entry(Enchants.SILK_SPAWNER, new int[]{1, 1}),
            Map.entry(Enchants.DECAPITATOR, new int[]{2, 2}),
            Map.entry(Enchants.ICE_ASPECT, new int[]{3, 10}),
            Map.entry(Enchants.THRIFTY, new int[]{3, 2}),
            Map.entry(Enchants.THUNDER, new int[]{5, 5}),
            Map.entry(Enchants.VENOM, new int[]{2, 10}),
            Map.entry(Enchants.WISDOM, new int[]{5, 5}),
            Map.entry(Enchants.WITHER, new int[]{2, 5}),
            Map.entry(Enchants.GHAST, new int[]{1, 1}),
            Map.entry(Enchants.TUNNEL, new int[]{3, 1}),
            Map.entry(Enchants.TELEKINESIS, new int[]{1, 1}));

    @GameTest
    public void allFifteenLoadWithPluginLevelAndWeight(GameTestHelper helper) {
        var registry = helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        helper.assertTrue(EXPECTED.size() == 15, "expected table has 15 entries");
        for (var entry : EXPECTED.entrySet()) {
            var holder = registry.get(entry.getKey()).orElse(null);
            helper.assertTrue(holder != null, "not registered: " + entry.getKey());
            helper.assertTrue(holder.value().getMaxLevel() == entry.getValue()[0],
                    entry.getKey() + " max level " + holder.value().getMaxLevel());
            helper.assertTrue(holder.value().definition().weight() == entry.getValue()[1],
                    entry.getKey() + " weight " + holder.value().definition().weight());
        }
        helper.succeed();
    }

    @GameTest
    public void pluginFormulas(GameTestHelper helper) {
        // Thunder: 5 + 2/level percent, 1.25 + 0.25/level damage.
        helper.assertTrue(Numbers.thunderChance(1) == 7 && Numbers.thunderChance(5) == 15, "thunder chance");
        helper.assertTrue(Numbers.thunderDamage(1) == 1.5 && Numbers.thunderDamage(5) == 2.5, "thunder damage");
        // Flame Walker: radius 1 + level, decay 8 + level seconds.
        helper.assertTrue(Numbers.flameWalkerRadius(1) == 2 && Numbers.flameWalkerRadius(2) == 3, "radius");
        helper.assertTrue(Numbers.flameWalkerDecaySeconds(2) == 10, "decay");
        // Ice Aspect slowness 3 + level seconds; Venom 5 + 5/level percent, 2 + level seconds.
        helper.assertTrue(Numbers.iceAspectSeconds(3) == 6 && Numbers.iceAspectSeconds(9) == 10, "ice seconds, capped at 10");
        helper.assertTrue(Numbers.venomChance(2) == 15 && Numbers.venomSeconds(2) == 4, "venom");
        // Wither 4 + 3/level percent, 4 + 2/level seconds.
        helper.assertTrue(Numbers.witherChance(2) == 10 && Numbers.witherSeconds(2) == 8, "wither");
        // Amplifier is the level value minus one.
        helper.assertTrue(Numbers.amplifier(1) == 0 && Numbers.amplifier(5) == 4, "amplifier");
        // Wisdom multiplies by 1 + 0.5/level, capped at 3, rounding up.
        helper.assertTrue(Numbers.wisdomMultiplier(1) == 1.5 && Numbers.wisdomMultiplier(5) == 3.0, "wisdom multiplier");
        helper.assertTrue(Numbers.wisdomXp(5, 1) == 8, "wisdom rounds up");
        // Decapitator 1 + 2/level, Thrifty 1/level, Silk Spawner 25/level.
        helper.assertTrue(Numbers.decapitatorChance(2) == 5 && Numbers.thriftyChance(3) == 3, "decap and thrifty");
        helper.assertTrue(Numbers.silkSpawnerChance(1) == 25, "silk spawner");
        // Ghast yield 2; Tunnel 2, 5, 9 blocks.
        helper.assertTrue(Numbers.ghastYield(1) == 2, "ghast yield");
        helper.assertTrue(Numbers.tunnelBlocks(1) == 2 && Numbers.tunnelBlocks(2) == 5 && Numbers.tunnelBlocks(3) == 9, "tunnel shape");
        helper.succeed();
    }
}
