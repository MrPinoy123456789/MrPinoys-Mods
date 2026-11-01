package pocketdungeons;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What wild mode forbids and protects inside a dungeon (decision 2026-10-30). PvP is on; a player who has just entered
 * takes no damage for {@code wildSpawnProtectionSeconds} unless they attack first. Ender pearls and chorus fruit are
 * blocked in the dungeon dimension (they only move a player around a sealed place and can break out of rooms), and so
 * are beds, respawn anchors, end crystals and TNT minecarts, which would be free kill tools with PvP on.
 *
 * <p>Teleport commands are not blocked: the owner wants {@code /tpa} and {@code /home} left alone, so
 * {@code wildBlockedCommands} defaults to empty and is not enforced yet (see the plan, task 2.11).
 */
final class WildRules {

    /** Player -> overworld game tick until which they are protected. */
    private static final Map<UUID, Long> PROTECTED_UNTIL = SessionState.perPlayer(new HashMap<>());

    /** Items that may not be used inside a wild dungeon. */
    private static final Set<Item> BLOCKED_ITEMS = Set.of(Items.ENDER_PEARL, Items.CHORUS_FRUIT, Items.END_CRYSTAL,
            Items.TNT_MINECART);

    private WildRules() {}

    static void register() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!WildMode.active() || !(entity instanceof ServerPlayer player)) {
                return true;
            }
            return !isProtected(player.getUUID(), player.level().getServer());
        });
        AttackEntityCallback.EVENT.register((player, level, hand, target, hit) -> {
            if (WildMode.active() && !level.isClientSide()) {
                PROTECTED_UNTIL.remove(player.getUUID());
            }
            return InteractionResult.PASS;
        });
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (blocksItem(player, player.getItemInHand(hand).getItem())) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (WildMode.active() && DungeonWorldRules.inDungeon(level)
                    && blocksBlock(level.getBlockState(hit.getBlockPos()).getBlock())) {
                return InteractionResult.FAIL;
            }
            if (blocksItem(player, player.getItemInHand(hand).getItem())) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });
    }

    /** Whether using {@code item} is forbidden for this player right now. */
    static boolean blocksItem(Player player, Item item) {
        return WildMode.active() && DungeonWorldRules.inDungeon(player.level()) && BLOCKED_ITEMS.contains(item);
    }

    /** Whether using this block is forbidden inside a wild dungeon (a bed or anchor would explode there). */
    static boolean blocksBlock(Block block) {
        return block instanceof BedBlock || block instanceof RespawnAnchorBlock;
    }

    // ---- spawn protection ----------------------------------------------------------

    /** Starts protection for a player who has just entered. */
    static void protect(ServerPlayer player, long nowTick) {
        int seconds = PocketDungeonsConfig.wildSpawnProtectionSeconds();
        if (seconds > 0) {
            PROTECTED_UNTIL.put(player.getUUID(), nowTick + seconds * 20L);
        }
    }

    static boolean isProtected(UUID player, MinecraftServer server) {
        Long until = PROTECTED_UNTIL.get(player);
        return until != null && server != null && server.overworld().getGameTime() < until;
    }

    /** Protection arithmetic without a server, for tests. */
    static boolean protectedAt(long untilTick, long nowTick) {
        return nowTick < untilTick;
    }

    /** Test seam: protects a player until a tick. */
    static void protectForTesting(UUID player, long untilTick) {
        PROTECTED_UNTIL.put(player, untilTick);
    }

    static void clearForTesting() {
        PROTECTED_UNTIL.clear();
    }

    /** Drops expired entries. Called from the wild watch tick. */
    static void tick(MinecraftServer server, long now) {
        if (PROTECTED_UNTIL.isEmpty()) {
            return;
        }
        long game = server.overworld().getGameTime();
        for (Iterator<Map.Entry<UUID, Long>> it = PROTECTED_UNTIL.entrySet().iterator(); it.hasNext(); ) {
            if (game >= it.next().getValue()) {
                it.remove();
            }
        }
    }
}
