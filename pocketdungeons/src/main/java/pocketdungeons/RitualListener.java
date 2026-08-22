package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The lodestone ritual: right-click a lodestone holding the key item and the
 * dungeon opens, with no command typed.
 *
 * <p>{@code /dungeon} stays available and free. This is a flavour entry point
 * and a soft item sink, not a gate -- making it the only way in would turn a
 * faucet into a locked door, which is the opposite of what the dungeon is for.
 */
final class RitualListener {

    private static final ConfiguredItem KEY = new ConfiguredItem("ritualKeyItem",
            PocketDungeonsConfig::ritualKeyItem,
            "the lodestone ritual is disabled until it is fixed. /dungeon still works.");

    private RitualListener() {}

    static void register() {
        UseBlockCallback.EVENT.register(RitualListener::onUseBlock);

        // Resolve the configured key once at startup rather than only on the
        // first click. A typo in ritualKeyItem is an operator's mistake to hear
        // about at boot, not something a player discovers by right-clicking a
        // lodestone and having nothing happen.
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (PocketDungeonsConfig.ritualEnabled()) {
                KEY.get();
            }
        });
    }

    private static InteractionResult onUseBlock(Player player, Level level,
                                                InteractionHand hand, BlockHitResult hit) {
        if (!PocketDungeonsConfig.ritualEnabled()) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        // Both hands fire this event; without the gate the ritual would try to
        // run twice for a single click.
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }

        BlockPos pos = hit.getBlockPos();
        if (!level.getBlockState(pos).is(Blocks.LODESTONE)) {
            return InteractionResult.PASS;
        }
        // Sneaking means "act on what I am holding, not on this block" -- it is
        // how vanilla lets you place against a block you would otherwise use, and
        // it is the escape hatch for an operator who points ritualKeyItem at
        // something placeable. Same guard kamutotems' Station uses.
        if (player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }

        // The exit pad is a lodestone too. Without this, standing on it and
        // right-clicking it would eat a key to "enter" a dungeon you are already
        // standing in. The dimension check covers a player who is in the void
        // without a live record -- the orphan-recovery case.
        if (Instances.hasInstance(serverPlayer)
                || serverPlayer.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
            return InteractionResult.PASS;
        }

        Item key = KEY.get();
        if (key == null) {
            return InteractionResult.PASS;
        }
        ItemStack held = player.getItemInHand(hand);
        if (!held.is(key) || held.getCount() < PocketDungeonsConfig.ritualKeyCount()) {
            return InteractionResult.PASS;
        }
        // The rule, not a kamutotems special case: the ritual only ever consumes
        // a stack carrying no custom_data at all. kamutotems sigils and this mod's
        // own boss stones are both echo shards with a custom_data tag, and the
        // default key item is an echo shard -- so without this, opening a dungeon
        // would destroy someone's sigil. PASS rather than FAIL so the event chain
        // continues and kamutotems' own use handler still gets its turn.
        if (!isPlainKey(held)) {
            return InteractionResult.PASS;
        }

        // Ahead of entry, so the player who is about to be teleported away is
        // still here to hear it.
        level.playSound(null, pos, SoundEvents.RESPAWN_ANCHOR_CHARGE,
                SoundSource.BLOCKS, 1.0f, 1.0f);

        // Consume only on success. enter() has failure paths -- missing dimension,
        // a stamp that could not be placed -- that leave the player exactly where
        // they stood, and eating a real item on those is a real loss.
        if (!Instances.enter(serverPlayer)) {
            return InteractionResult.PASS;
        }
        held.shrink(PocketDungeonsConfig.ritualKeyCount());
        serverPlayer.sendSystemMessage(Component.literal("The lodestone pulls you under.")
                .withStyle(ChatFormatting.DARK_PURPLE));
        return InteractionResult.SUCCESS_SERVER;
    }

    /**
     * A key is a stack with no {@code custom_data} whatsoever. Narrowing this to
     * "no kamutotems compound" would be the same amount of code and would be
     * wrong about every tagged item nobody has written yet.
     */
    private static boolean isPlainKey(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null || data.isEmpty();
    }
}
