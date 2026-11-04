package pocketdungeons;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Every wild dungeon ends in a boss (owner, 2026-11-04). When the floor's trial spawners are cleared the dungeon's own
 * finale runs, the same {@code FinaleWave} fight a campaign final floor has: the title says the room stirs, a wave
 * comes with a named elite and a boss bar, and the Exit does not appear until it is dead. A wild floor is never a
 * dungeon's final node, so {@code FinaleWave.finaleOf} asks here instead of {@code TripView.onFinal}. The dungeons that
 * author no finale (the Endless Mines and the End) get one from {@link #FALLBACK}. The elite's health grows with the
 * level ({@link #HEALTH_PERCENT_PER_LEVEL}) on top of the party scaling every finale already has.
 *
 * <p>The kill pays on top of the clear: the boss table ({@code boss/tier_N_wild}, ominous {@code tier_N_ominous_wild},
 * written by {@code tools/gen_wild_loot.py}) goes in the copper chest, and every player inside gets Valor through
 * {@link #VALOR_COMMAND}. The won finale also adds the campaign's finale reward chest to the barrel.
 */
final class WildBoss {

    // Hardcoded for now: switch these to pocketdungeons.json knobs (tools/new_knob.py --regen) when available.
    /** Extra elite health per level, in percent: a level 10 boss has 50 percent more than its dungeon's own. */
    static final int HEALTH_PERCENT_PER_LEVEL = 5;
    /** Run once per player inside when the boss falls; empty turns it off. Charter Currency's admin add. */
    static final String VALOR_COMMAND = "chartercurrency add %player% %amount% valor";
    /** Valor for the kill: from {@code 5 + level / 2} to {@code 10 + level}, never more than this. */
    static final int VALOR_MAX = 20;

    /** The boss of a dungeon that authors no finale, by dungeon id; anything else unlisted gets {@link #DEFAULT}. */
    static final Map<String, DungeonDef.Finale> FALLBACK = Map.of(
            "pocketdungeons:endless_mine", finale("The Pit Boss", "minecraft:vindicator", 60, "minecraft:iron_pickaxe",
                    "minecraft:zombie", 4, "minecraft:skeleton", 2),
            "pocketdungeons:endless_mine_deep", finale("The Deep Delver", "minecraft:vindicator", 70,
                    "minecraft:diamond_pickaxe", "minecraft:skeleton", 4, "minecraft:cave_spider", 3),
            "pocketdungeons:endless_mine_chart", finale("The Chartless", "minecraft:vindicator", 70,
                    "minecraft:diamond_pickaxe", "minecraft:husk", 4, "minecraft:skeleton", 3),
            "pocketdungeons:the_end", finale("The Orchard Warden", "minecraft:enderman", 90, "",
                    "minecraft:endermite", 4, "minecraft:skeleton", 3));
    static final DungeonDef.Finale DEFAULT = finale("The Keeper", "minecraft:vindicator", 60, "minecraft:iron_axe",
            "minecraft:zombie", 4, "minecraft:skeleton", 2);

    private WildBoss() {}

    private static DungeonDef.Finale finale(String name, String elite, int health, String mainhand,
                                            String mobA, int countA, String mobB, int countB) {
        return new DungeonDef.Finale(List.of(new DungeonDef.FinaleMob(mobA, countA), new DungeonDef.FinaleMob(mobB, countB)),
                50, new DungeonDef.Elite(elite, name, health, mainhand));
    }

    /**
     * The finale a wild floor fights: its dungeon's own (elite health grown by level), or the fallback. {@code null}
     * when the dungeon has a finale script and no data wave: {@code FinaleWave} then runs the script as it would on a
     * final floor.
     */
    static DungeonDef.Finale finaleFor(DungeonDef def, int level) {
        DungeonDef.Finale own = def.finale();
        if (own == null) {
            if (FinaleScripts.has(def.id())) {
                return null;
            }
            own = FALLBACK.getOrDefault(def.id(), DEFAULT);
        }
        DungeonDef.Elite elite = own.elite();
        if (elite == null) {
            return own;
        }
        return new DungeonDef.Finale(own.mobs(), own.perMemberPercent(), new DungeonDef.Elite(elite.type(),
                elite.name(), scaledHealth(elite.health(), level), elite.mainhand()));
    }

    /** The elite's health at a level. Pure. */
    static int scaledHealth(int health, int level) {
        return health * (100 + HEALTH_PERCENT_PER_LEVEL * Math.max(0, level)) / 100;
    }

    /** The lowest and highest Valor a kill at this level pays. Pure. */
    static int[] valorRange(int level) {
        int lo = Math.min(VALOR_MAX, 5 + Math.max(0, level) / 2);
        int hi = Math.min(VALOR_MAX, 10 + Math.max(0, level));
        return new int[] {lo, Math.max(lo, hi)};
    }

    /** The boss table a kill rolls. Pure. */
    static String lootTable(int tier, boolean ominous) {
        int t = Math.max(1, Math.min(4, tier));
        return PocketDungeonsMod.MOD_ID + ":boss/tier_" + t + (ominous ? "_ominous" : "") + WildLoot.SUFFIX;
    }

    /** The boss's loot, for the copper chest. Empty when the table is missing (logged). */
    static List<ItemStack> rollLoot(ServerLevel level, InstanceRecord record) {
        Identifier id = Identifier.parse(lootTable(record.layout.lootTier(), record.layout.ominous()));
        ResourceKey<LootTable> key = ResourceKey.create(Registries.LOOT_TABLE, id);
        MinecraftServer server = level.getServer();
        if (server == null || !LootTables.exists(server, key)) {
            PocketDungeonsMod.LOG.error("The wild boss table {} is missing", id);
            return List.of();
        }
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(record.layout.terminal()))
                .create(LootContextParamSets.CHEST);
        ObjectArrayList<ItemStack> rolled = server.reloadableRegistries().getLootTable(key)
                .getRandomItems(params, level.getRandom().nextLong());
        return new ArrayList<>(rolled);
    }

    /** Pays every player inside their Valor for the kill and tells them. */
    static void payValor(MinecraftServer server, List<ServerPlayer> players, int level, RandomSource random) {
        if (VALOR_COMMAND.isBlank()) {
            return;
        }
        int[] range = valorRange(level);
        for (ServerPlayer player : players) {
            int amount = range[0] + random.nextInt(range[1] - range[0] + 1);
            String command = VALOR_COMMAND.replace("%player%", player.getName().getString())
                    .replace("%amount%", Integer.toString(amount));
            try {
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
                player.sendSystemMessage(Component.literal(WildText.bossValor(amount)).withStyle(ChatFormatting.GOLD));
            } catch (Exception e) {
                PocketDungeonsMod.LOG.error("The wild boss Valor command '{}' failed", command, e);
            }
        }
    }
}
