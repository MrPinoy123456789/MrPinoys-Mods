package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity;
import net.minecraft.world.level.block.entity.vault.VaultState;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Playtest 2026-10-03-2: a vault pays a party once, not once per member.
 *
 * <p>Vanilla gives every player one opening of every vault, and a trial
 * spawner ejects a key for every player it saw, so a party of two looted
 * each vault twice ("each player opens each vault, 4x"). The owner kept the
 * keys as they are and asked for the loot not to double. When a party member
 * unlocks a vault, every member of that run is marked as rewarded by it, so
 * the next one's key is refused the way a second key from the same player
 * always was.
 *
 * <p>Whether the click unlocked the vault is read a tick later from the block
 * state: a vault that went from active to unlocking accepted a key. The
 * vault's own record of who it paid is not public, and this keeps the
 * one-mixin budget.
 */
final class VaultShare {

    private record Pending(ServerLevel level, BlockPos pos, UUID opener, int ticks) {}

    private static final List<Pending> PENDING = new ArrayList<>();

    private VaultShare() {}

    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(VaultShare::tick);
    }

    /** Called for every right-click on a vault in the dungeon, before vanilla handles it. */
    static void clicked(ServerPlayer player, ServerLevel level, BlockPos pos) {
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
            shareWithParty(server, p.level(), p.pos(), p.opener());
        }
    }

    /**
     * Marks every online member of {@code opener}'s run as rewarded by the
     * vault at {@code pos}. Package private so a gametest can drive it.
     *
     * @return how many members were marked
     */
    static int shareWithParty(MinecraftServer server, ServerLevel level, BlockPos pos, UUID opener) {
        InstanceRecord record = InstanceRegistry.byMember.get(opener);
        if (record == null || !(level.getBlockEntity(pos) instanceof VaultBlockEntity vault)) {
            return 0;
        }
        int marked = 0;
        for (UUID member : record.members.keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(member);
            if (player != null && !member.equals(opener)) {
                vault.getServerData().addToRewardedPlayers(player);
                marked++;
            }
        }
        if (marked > 0) {
            vault.setChanged();
        }
        return marked;
    }
}
