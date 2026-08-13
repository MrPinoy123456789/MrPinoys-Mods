package spiritwolves;

import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * Right-click a bound Spirit Stone to summon the wolf; right-click again to
 * recall it. Both free and unlimited (charges are lives, not summons -- see
 * SPEC.md section 2).
 *
 * <p>v3 (SPEC.md section 16.4): reads/writes {@link PlayerWolfRegistry}
 * instead of the stone. Any bound stone in the player's hand operates on the
 * same registry record.
 */
public final class Summoning {

    private Summoning() {}

    public static void register() {
        UseItemCallback.EVENT.register(Summoning::onUseItem);
    }

    private static InteractionResult onUseItem(net.minecraft.world.entity.player.Player player,
                                                net.minecraft.world.level.Level level,
                                                InteractionHand hand) {
        if (!(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.PASS;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (!SpiritStone.isBound(stack)) {
            return InteractionResult.PASS;
        }

        WolfRecord record = PlayerWolfRegistry.get(serverPlayer.getUUID());
        if (record == null) {
            // Orphan: a bound stone with no registry record -- either a released
            // wolf's leftover remote, or a legacy v1/v2 stone (SPEC.md section
            // 16.9). Either way it self-heals to unbound on this touch, ready to
            // bind a new wolf.
            SpiritStone.revertToUnbound(stack);
            serverPlayer.sendSystemMessage(Component.literal(
                            "The stone is silent. You have no spirit wolf.")
                    .withStyle(ChatFormatting.GRAY));
            return InteractionResult.FAIL;
        }

        Wolf existing = findWolf(serverLevel, record.wolfUuid);

        if (record.summoned && existing != null) {
            // Already out in this dimension -- recall it into the stone, unless it's still committed
            // to whatever it's doing (SPEC.md section 15.7).
            int lockedFor = RecallLock.secondsRemaining(existing, serverLevel);
            if (lockedFor > 0) {
                serverPlayer.sendSystemMessage(Component.literal(
                                "Your wolf won't come back yet -- " + lockedFor + "s.")
                        .withStyle(ChatFormatting.RED));
                return InteractionResult.FAIL;
            }
            recall(serverPlayer, serverLevel, stack, record, existing);
            return InteractionResult.SUCCESS;
        }

        if (record.summoned && existing == null) {
            // Registry thinks the wolf is out, but it isn't in this dimension.
            // It may have been left behind during a rapid dimension hop.
            Wolf elsewhere = findWolfAnywhere(serverLevel.getServer(), record.wolfUuid);
            if (elsewhere != null) {
                String dimName = elsewhere.level().dimension().toString();
                recall(serverPlayer, (ServerLevel) elsewhere.level(), stack, record, elsewhere);
                serverPlayer.sendSystemMessage(Component.literal(
                                "Your wolf returns to the stone from " + dimName + ".")
                        .withStyle(ChatFormatting.GRAY));
                return InteractionResult.SUCCESS;
            }
            // Wolf is gone -- reconcile so the next click summons a fresh one.
            record.summoned = false;
            PlayerWolfRegistry.markDirty(serverPlayer.getUUID());
        }

        // Not out -- try to summon it.
        if (SpiritStone.chargesRemaining(stack) <= 0) {
            serverPlayer.sendSystemMessage(Component.literal(
                            "Your wolf is dormant. Repair the stone at anvil.")
                    .withStyle(ChatFormatting.RED));
            return InteractionResult.FAIL;
        }

        summon(serverPlayer, serverLevel, stack, record);
        return InteractionResult.SUCCESS;
    }

    /** Looks up a live wolf by UUID in the level -- the duplicate-prevention check from SPEC.md section 8. */
    static Wolf findWolf(ServerLevel level, UUID uuid) {
        if (uuid == null) {
            return null;
        }
        Entity entity = level.getEntity(uuid);
        return entity instanceof Wolf wolf ? wolf : null;
    }

    /** Looks up a live wolf by UUID across every loaded dimension. */
    static Wolf findWolfAnywhere(MinecraftServer server, UUID uuid) {
        if (uuid == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(uuid);
            if (entity instanceof Wolf wolf) {
                return wolf;
            }
        }
        return null;
    }

    private static void summon(ServerPlayer player, ServerLevel level, ItemStack stack, WolfRecord record) {
        Wolf wolf = WolfCapture.restore(record.wolfTag, level,
                player.getX(), player.getY(), player.getZ(), player.getYRot(), 0f);
        if (wolf == null) {
            player.sendSystemMessage(Component.literal("The wolf's spirit would not answer.")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        record.wolfUuid = wolf.getUUID();

        // A fresh outing starts at streak zero, so this clears any leftover buffs
        // rather than granting them.
        Streak.applyBuffs(wolf, 0);
        RecallLock.lock(wolf, level);

        record.summoned = true;
        record.lastSummonedAt = System.currentTimeMillis();
        record.wolfName = WolfCapture.nameOf(wolf);
        record.collar = WolfCapture.collarOf(wolf);
        PlayerWolfRegistry.markDirty(player.getUUID());
        SpiritStone.refreshLore(stack, record);

        Chime.summoned(player);
        String name = record.wolfName;
        player.sendSystemMessage(Component.literal(
                        (name != null ? name : "Your wolf") + " answers.")
                .withStyle(ChatFormatting.AQUA));
    }

    private static void recall(ServerPlayer player, ServerLevel level, ItemStack stack, WolfRecord record, Wolf wolf) {
        // Ends the outing: records the high-water mark, drops the live streak.
        Streak.onReturn(wolf, record);
        RecallLock.forget(wolf.getUUID());
        AbilityProcs.forget(wolf.getUUID());

        record.wolfTag = WolfCapture.capture(wolf, level);
        record.wolfName = WolfCapture.nameOf(wolf);
        record.collar = WolfCapture.collarOf(wolf);
        record.summoned = false;
        PlayerWolfRegistry.markDirty(player.getUUID());
        SpiritStone.refreshLore(stack, record);

        wolf.discard();

        Chime.recalled(player);
        String name = record.wolfName;
        player.sendSystemMessage(Component.literal(
                        (name != null ? name : "The wolf") + " returns to the stone.")
                .withStyle(ChatFormatting.GRAY));
    }
}
