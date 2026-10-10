package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerState;
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity;
import net.minecraft.world.level.block.entity.vault.VaultState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import pocketdungeons.mixin.TrialSpawnerStateDataAccessor;
import pocketdungeons.mixin.VaultServerDataAccessor;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Playtest 2026-10-03-2: how a party is paid by spawners and vaults.
 *
 * <p>Vanilla pays per player twice over: a trial spawner ejects its reward
 * once for every player it saw, and a vault opens once for every player, so
 * a party of two looted each vault twice with two keys a spawner ("4x").
 * The owner's rule (2026-10-03):
 * <ul>
 *   <li>one key per trial spawner, whatever the party size;</li>
 *   <li>one opening per vault per party;</li>
 *   <li>that opening pays one loot roll per party member.</li>
 * </ul>
 *
 * <p>The spawner is held to one reward by trimming the players it rewards
 * once it has been beaten (its reward phase), so the per-player mob counts
 * while it fights are untouched. A vault's unlock is read a tick or two after
 * the click from its block state: a vault that went from active to unlocking
 * accepted a key, and it is still in its unlock delay, so the extra rolls join
 * the items it is about to eject.
 */
final class PartyRewards {

    private record Pending(ServerLevel level, BlockPos pos, UUID opener, int ticks) {}

    private static final List<Pending> PENDING = new ArrayList<>();

    /** Ticks between spawner checks: well inside the 40 ticks a beaten spawner waits before it ejects. */
    private static final int SPAWNER_CHECK_TICKS = 5;

    private PartyRewards() {}

    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(PartyRewards::tick);
    }

    // ---- spawners -------------------------------------------------------------------

    /**
     * Holds every beaten spawner of {@code record}'s floor to one reward.
     * Package private so a gametest can drive it.
     */
    static void oneRewardPerSpawner(ServerLevel level, Iterable<BlockPos> spawners) {
        for (BlockPos pos : spawners) {
            if (!(level.getBlockEntity(pos) instanceof TrialSpawnerBlockEntity spawner)) {
                continue;
            }
            TrialSpawnerState state = spawner.getState();
            if (state != TrialSpawnerState.WAITING_FOR_REWARD_EJECTION
                    && state != TrialSpawnerState.EJECTING_REWARD) {
                continue;
            }
            Set<UUID> rewarded = ((TrialSpawnerStateDataAccessor) (Object)
                    spawner.getTrialSpawner().getStateData()).pocketdungeons$detectedPlayers();
            if (rewarded.size() > 1) {
                Iterator<UUID> it = rewarded.iterator();
                it.next();
                while (it.hasNext()) {
                    it.next();
                    it.remove();
                }
                spawner.setChanged();
            }
        }
    }

    // ---- vaults ---------------------------------------------------------------------

    /** Called for every right-click on a vault in the dungeon, before vanilla handles it. */
    static void vaultClicked(ServerPlayer player, ServerLevel level, BlockPos pos) {
        if (!level.getBlockState(pos).is(Blocks.VAULT)
                || level.getBlockState(pos).getValue(VaultBlock.STATE) != VaultState.ACTIVE) {
            return;
        }
        InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
        if (record == null || record.members.size() < 2) {
            return;
        }
        PENDING.add(new Pending(level, pos.immutable(), player.getUUID(), 2));
    }

    private static void tick(MinecraftServer server) {
        if (server.getTickCount() % SPAWNER_CHECK_TICKS == 0) {
            ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
            if (level != null) {
                for (InstanceRecord record : InstanceRegistry.bySlot.values()) {
                    if (record.layout != null && record.members.size() > 1) {
                        oneRewardPerSpawner(level, record.layout.trialSpawners());
                    }
                }
            }
        }
        if (PENDING.isEmpty()) {
            return;
        }
        List<Pending> due = new ArrayList<>();
        for (int i = PENDING.size() - 1; i >= 0; i--) {
            Pending p = PENDING.get(i);
            if (p.ticks() <= 1) {
                due.add(PENDING.remove(i));
            } else {
                PENDING.set(i, new Pending(p.level(), p.pos(), p.opener(), p.ticks() - 1));
            }
        }
        for (Pending p : due) {
            if (!p.level().getBlockState(p.pos()).is(Blocks.VAULT)
                    || p.level().getBlockState(p.pos()).getValue(VaultBlock.STATE) == VaultState.ACTIVE) {
                continue;
            }
            payParty(server, p.level(), p.pos(), p.opener());
        }
    }

    /**
     * The vault at {@code pos} was just unlocked by {@code opener}: adds one
     * more roll of its loot table for every other party member, and marks
     * them as paid by it, so the party opens it once. Package private so a
     * gametest can drive it.
     *
     * @return how many extra rolls were added
     */
    static int payParty(MinecraftServer server, ServerLevel level, BlockPos pos, UUID opener) {
        InstanceRecord record = InstanceRegistry.byMember.get(opener);
        if (record == null || !(level.getBlockEntity(pos) instanceof VaultBlockEntity vault)) {
            return 0;
        }
        ServerPlayer openerPlayer = server.getPlayerList().getPlayer(opener);
        int extra = 0;
        for (UUID member : record.members.keySet()) {
            if (member.equals(opener)) {
                continue;
            }
            extra++;
            ServerPlayer player = server.getPlayerList().getPlayer(member);
            if (player != null) {
                vault.getServerData().addToRewardedPlayers(player);
            }
        }
        if (extra == 0 || openerPlayer == null) {
            return 0;
        }
        ResourceKey<LootTable> key = vault.getConfig().lootTable();
        LootTable table = server.reloadableRegistries().getLootTable(key);
        VaultServerDataAccessor data = (VaultServerDataAccessor) (Object) vault.getServerData();
        List<ItemStack> items = new ArrayList<>(data.pocketdungeons$getItemsToEject());
        for (int i = 0; i < extra; i++) {
            LootParams.Builder params = new LootParams.Builder(level)
                    .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                    .withParameter(LootContextParams.TOOL, vault.getConfig().keyItem())
                    .withParameter(LootContextParams.THIS_ENTITY, openerPlayer)
                    .withLuck(openerPlayer.getLuck());
            items.addAll(table.getRandomItems(params.create(LootContextParamSets.VAULT),
                    level.getRandom().nextLong()));
        }
        data.pocketdungeons$setItemsToEject(items);
        vault.setChanged();
        return extra;
    }
}
