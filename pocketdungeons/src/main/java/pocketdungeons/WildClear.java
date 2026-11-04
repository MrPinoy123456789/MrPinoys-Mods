package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The clear of a wild dungeon: the same gate the campaign pad uses, checked by a watcher instead of a pad. When it
 * passes, the floor pays like one cleared campaign floor (barrel and copper chest at the far side of the terminal),
 * the clear Exit block appears, the cooldown starts, and the dungeon stays open until {@link WildReset} closes it.
 * Nothing here advances the floor or builds a next staging room.
 */
final class WildClear {

    /**
     * The gate is asked once per watch tick ({@code watchIntervalTicks}, 20 by default), which is slow enough that the
     * spawner scan is not free. It must not also be tied to the world clock: the watch counts ticks from server start,
     * so a test of the world clock's remainder lines up only on a restart whose boot offset happens to be
     * a multiple of 10, and on any other never passes (owner, 2026-11-02: every spawner done, no exit, no rewards).
     */

    private static final java.util.Map<String, Boolean> WARNED = SessionState.clearedAtStop(new java.util.HashMap<>());

    private WildClear() {}

    /**
     * Whether a floor may auto clear at all. A floor with no spawner, no objective and no boss would pass the gate
     * the moment someone entered ({@code spawnersCleared} is true for a total of zero), so it never clears.
     */
    static boolean hasSomethingToClear(InstanceRecord record, ServerLevel level) {
        return !TrialContent.activeSpawners(record.layout, level).isEmpty()
                || FloorObjectives.gatesPad(record)
                || ZoneRules.bossCapstone(record.floor.theme);
    }

    /** One watch tick of an in progress wild record. */
    static void tick(MinecraftServer server, InstanceRecord record, WildState.Lodestone lodestone, long gameTime) {
        if (lodestone == null || lodestone.phase != WildState.Phase.IN_PROGRESS || record.members.isEmpty()) {
            return;
        }
        ServerLevel level = WildReset.dungeonLevel(server, lodestone.id);
        if (level == null) {
            return;
        }
        if (!hasSomethingToClear(record, level)) {
            if (WARNED.putIfAbsent(lodestone.id, Boolean.TRUE) == null) {
                PocketDungeonsMod.LOG.warn("Wild lodestone {} has no spawner, objective or boss; it will never clear",
                        lodestone.id);
            }
            return;
        }
        if (RunLifecycle.padRefusal(server, record, level) == null) {
            clear(server, level, record, lodestone);
        }
    }

    /** Pays the clear and starts the cooldown. Package private so a gametest can force it. */
    static void clear(MinecraftServer server, ServerLevel level, InstanceRecord record, WildState.Lodestone lodestone) {
        WildDungeons.Offering offering = WildDungeons.byId(lodestone.dungeon);
        DungeonDef def = TripView.def(record);
        BlockPos terminal = record.layout.terminal();
        DoorMask.Direction entranceDir = CellGeometry.terminalEntranceDirection(record.layout.geometry(), terminal);
        List<ItemStack> bonus = new ArrayList<>(WildRewards.rollBonus(offering, level.getRandom()));
        // The boss's own loot rides in the copper chest with the bonus (decision 2026-11-04).
        boolean bossDown = record.floor.finaleWon;
        if (bossDown) {
            bonus.addAll(WildBoss.rollLoot(level, record));
        }
        RunLifecycle.placeFloorRewards(server, level, record, def, false, terminal, entranceDir, 1, bonus);
        BlockPos exit = TrialContent.exitSpot(terminal, entranceDir);
        WildLodestones.placeExit(level, exit, lodestone.id, true);

        boolean ominous = record.layout.ominous();
        long cooldown = WildOmen.cooldownSeconds((offering == null ? 60L : offering.cooldownMinutes()) * 60L, ominous);
        long now = WildClock.now();
        lodestone.markCleared(now, cooldown);

        List<ServerPlayer> players = onlineMembers(server, record);
        List<WildKingdoms.Side> sides = new ArrayList<>();
        for (ServerPlayer player : players) {
            sides.add(WildKingdoms.of(player));
        }
        WildKingdoms.Side winner = WildKingdoms.majority(sides);
        if (winner != null) {
            lodestone.recordClear(winner.name(), winner.colour(), now);
            ServerLevel overworld = WildReset.levelOf(server, lodestone);
            if (overworld != null) {
                WildBanner.ensure(overworld, lodestone);
            }
        }
        WildStore.save();

        int runLevel = record.layout.keystoneLevel();
        for (ServerPlayer player : players) {
            player.sendSystemMessage(Component.literal(WildText.CLEARED).withStyle(ChatFormatting.GREEN));
            // Valor and kingdom Renown, through the operator's payoutCommand (Charter Currency's earn), as a campaign
            // floor pays (decision 2026-11-04).
            Payout.runPayoutCommand(player, runLevel, 1);
        }
        if (bossDown) {
            WildBoss.payValor(server, players, runLevel, level.getRandom());
        }
        WildRewards.pay(new WildRewards.Payout(lodestone, offering, runLevel, players));
        PocketDungeonsMod.LOG.info("Wild lodestone {} cleared; reset in {} seconds", lodestone.id, cooldown);
    }

    static List<ServerPlayer> onlineMembers(MinecraftServer server, InstanceRecord record) {
        List<ServerPlayer> out = new ArrayList<>();
        for (UUID member : record.members.keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(member);
            if (player != null) {
                out.add(player);
            }
        }
        return out;
    }
}
