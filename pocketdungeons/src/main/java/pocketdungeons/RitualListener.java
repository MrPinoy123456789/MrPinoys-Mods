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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The lodestone ritual: right-click a lodestone holding the key item and the
 * dungeon opens, with no command typed.
 *
 * <h2>U7 inverts the key rule</h2>
 *
 * <p>U4's rule was "consume only a stack with <em>no</em> {@code custom_data} at
 * all", which was right when the key was a plain echo shard and the danger was
 * eating somebody's kamutotems sigil. It is now the exact opposite: consume
 * <strong>only</strong> a stack carrying {@code pocketdungeons.keystone}, and
 * {@code PASS} on everything else -- sigils included, for exactly U4's reasoning,
 * and now for free, because the test is positive rather than a list of things to
 * avoid. Lodestone plus keystone is the font.
 *
 * <p>An ominous bottle in the other hand -- or a keystone carrying the ominous
 * affix -- starts an ominous run instead (U6 Stage 5).
 *
 * <p>{@code /dungeon} still works and still costs the same keystone. This is a
 * flavour entry point, not a gate: it is the <em>lodestone</em> that is optional,
 * never the key.
 */
final class RitualListener {

    private RitualListener() {}

    static void register() {
        UseBlockCallback.EVENT.register(RitualListener::onUseBlock);

        // Resolve every configured item once at startup rather than on the first
        // click. A typo is an operator's mistake to hear about at boot, not
        // something a player discovers by right-clicking a lodestone and having
        // nothing happen.
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            Keystone.warmUp();
            TrialContent.warmUp();
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

        // The positive test that replaced U4's "no custom_data at all". A
        // kamutotems sigil is not a keystone, so it PASSes here and the event
        // chain continues to kamutotems' own use handler untouched -- the exact
        // outcome U4 wanted, reached by a rule that cannot be wrong about a tagged
        // item nobody has written yet.
        ItemStack held = player.getItemInHand(hand);
        if (!Keystone.isKeystone(held)) {
            return InteractionResult.PASS;
        }

        // An ominous bottle in the off hand buys the stakes. The bottle is checked
        // rather than consumed here; consumeOminousBottle runs only after entry
        // succeeded, on the same rule the keystone follows.
        boolean bottle = hasOminousBottle(serverPlayer);

        // Ahead of entry, so the player who is about to be teleported away is
        // still here to hear it.
        level.playSound(null, pos, SoundEvents.RESPAWN_ANCHOR_CHARGE,
                SoundSource.BLOCKS, 1.0f, 1.0f);

        // Consume only on success. enterWithKeystone() has failure paths --
        // missing dimension, a stamp that could not be placed -- that leave the
        // player exactly where they stood, and eating a keystone somebody spent
        // several runs earning on one of those is a real loss.
        if (!Instances.enterWithKeystone(serverPlayer, bottle)) {
            return InteractionResult.PASS;
        }
        if (bottle) {
            consumeOminousBottle(serverPlayer);
        }
        serverPlayer.sendSystemMessage(Component.literal("The lodestone pulls you under.")
                .withStyle(ChatFormatting.DARK_PURPLE));
        return InteractionResult.SUCCESS_SERVER;
    }

    /**
     * Whether this player is offering an ominous bottle.
     *
     * <p>Off hand only, so the main hand stays free for the keystone and neither
     * choice has to be made by juggling. {@code ominousRequiresBottle: false} lets
     * an operator make the stakes free, in which case
     * {@code /dungeon ominous} is the route and this stays the paid one.
     */
    private static boolean hasOminousBottle(ServerPlayer player) {
        ItemStack offhand = player.getItemInHand(InteractionHand.OFF_HAND);
        return offhand.is(Items.OMINOUS_BOTTLE) && offhand.get(DataComponents.CUSTOM_DATA) == null;
    }

    private static void consumeOminousBottle(ServerPlayer player) {
        player.getItemInHand(InteractionHand.OFF_HAND).shrink(1);
    }
}
