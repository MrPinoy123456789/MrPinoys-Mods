package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;

import java.util.UUID;

/**
 * The lodestone ritual: right-click a lodestone holding the keystone and the
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
 * <h2>U8 Stage 4: one lodestone, three destinations</h2>
 *
 * <p>Right-clicking a lodestone with the compass now branches on server state
 * (T14): a pending door offer routes to the selector room, an owned live
 * instance re-enters it for free, and anything else opens a fresh run. Ominous
 * is no longer requested here at all -- it rides entirely on the keystone's own
 * affix (U8 Stage 6).
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
            CallingCard.warmUp();
            TrialContent.warmUp();
        });
    }

    private static InteractionResult onUseBlock(Player player, Level level,
                                                InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        // Both hands fire this event; without the gate the ritual would try to
        // run twice for a single click.
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }

        BlockPos pos = hit.getBlockPos();

        // M2 T2.2: the room's permission mask, ahead of everything else below --
        // a denied container open or a denied placement must never fall through
        // to the ritual or to vanilla's own handling of the block. Positional
        // (Instances.roomOwnerAt is one bounds check per live instance) and
        // early-returns the moment the position is not inside anyone's room, so
        // the common case -- the other 15 cells of the dungeon, or the
        // overworld entirely -- costs exactly that one check.
        if (RoomProtection.denyContainerUse(level, player, pos, level.getBlockEntity(pos))) {
            return InteractionResult.FAIL;
        }
        // Placement: there is no generic "before a block is placed" event in
        // this mod's Fabric API surface, so this is the same trick as the
        // container check above -- deny the use-on-block interaction that a
        // placement begins from when the target face sits inside someone else's
        // room and the held item would place something there.
        UUID placementRoomOwner = Instances.roomOwnerAt(hit.getBlockPos().relative(hit.getDirection()));
        if (placementRoomOwner != null
                && player.getItemInHand(hand).getItem() instanceof net.minecraft.world.item.BlockItem
                && !RoomProtection.isPermitted(level, player, placementRoomOwner)) {
            return InteractionResult.FAIL;
        }

        // M2/M3: a door in the player's own lobby. Handled mod-side and ahead
        // of everything else -- vanilla's own door open/close must never run
        // for one of these, or a door that swings looks like it did something.
        Integer step = Instances.selectorDoorStep(serverPlayer, pos);
        if (step != null) {
            sendDoorOffer(serverPlayer, step);
            return InteractionResult.SUCCESS_SERVER;
        }

        if (!PocketDungeonsConfig.ritualEnabled()) {
            return InteractionResult.PASS;
        }
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

        // The positive test that replaced U4's "no custom_data at all". A
        // kamutotems sigil is not a keystone, so it PASSes here and the event
        // chain continues to kamutotems' own use handler untouched -- the exact
        // outcome U4 wanted, reached by a rule that cannot be wrong about a tagged
        // item nobody has written yet.
        ItemStack held = player.getItemInHand(hand);

        // M3 T3.2: calling card -- positive test, falls through to the keystone
        // branch if it is not a card, and foreign items still PASS cleanly.
        java.util.Optional<java.util.UUID> cardOwner = CallingCard.ownerOf(held);
        if (cardOwner.isPresent()) {
            level.playSound(null, pos, SoundEvents.RESPAWN_ANCHOR_CHARGE,
                    SoundSource.BLOCKS, 1.0f, 1.0f);
            if (Instances.visit(serverPlayer, cardOwner.get())) {
                return InteractionResult.SUCCESS_SERVER;
            }
            return InteractionResult.PASS;
        }

        if (!Keystone.isKeystone(held)) {
            return InteractionResult.PASS;
        }

        // The exit pad is a lodestone too. Without this, standing on it and
        // right-clicking it would eat a key to "enter" a dungeon you are already
        // standing in. The dimension check covers a player who is in the void
        // without a live record -- the orphan-recovery case. Free re-entry (T5)
        // is handled by enterWithKeystone itself, ahead of any keystone spend.
        if (Instances.hasInstance(serverPlayer)
                || serverPlayer.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
            return InteractionResult.PASS;
        }

        // Ahead of entry, so the player who is about to be teleported away is
        // still here to hear it.
        level.playSound(null, pos, SoundEvents.RESPAWN_ANCHOR_CHARGE,
                SoundSource.BLOCKS, 1.0f, 1.0f);

        // The keystone is a remote, not a cost: it is not consumed on entry.
        // enterWithKeystone() still has failure paths -- missing dimension, a stamp
        // that could not be placed -- that leave the player exactly where they
        // stood, so the sound and message below only fire on success.
        if (!Instances.enterWithKeystone(serverPlayer)) {
            return InteractionResult.PASS;
        }
        serverPlayer.sendSystemMessage(Component.literal("The lodestone pulls you under.")
                .withStyle(ChatFormatting.DARK_PURPLE));
        return InteractionResult.SUCCESS_SERVER;
    }

    /**
     * The offer behind one door, as a dialog.
     *
     * <p>Was three chat lines and a {@code [ Take this key ]} link; it is now the
     * same heading, the same affix warning and the same {@code /dungeon choose
     * <step>} click, rendered as a vanilla {@code NoticeDialog}. Nothing about
     * what a door does changed -- the button runs the command the link ran, and
     * {@code /dungeon choose} still works typed. A dialog is a runtime value sent
     * with {@code Holder.direct}, so this needs nothing installed client-side and
     * leaves the server-only rule intact.
     *
     * <p>Pushed rather than hung off a chat message, unlike every other screen in
     * this mod: the player right-clicked the door a tick ago, so the screen is the
     * direct answer to an action they just took, not an interruption.
     *
     * <p>M2/M3: rendered from the player's <em>current</em> keystone level,
     * not a banked "pending offer" -- the doors in the lobby are always live,
     * every visit, not a one-time reward after a completed run.
     */
    private static void sendDoorOffer(ServerPlayer player, int step) {
        DungeonLog.Entry entry = DungeonLog.forServer(player.level().getServer()).get(player.getUUID());
        int level = Math.max(1, entry.keystoneLevel());
        Keystone.Offer[] offers = Keystone.offers(player.getUUID(), level, entry.recentThemes());
        Keystone.Offer offer = offers[Math.min(step - 1, offers.length - 1)];

        java.util.List<Affix> ordered = AffixMath.ordered(offer.affixes());
        String doorAffix = ordered.isEmpty() ? "Oak" : ordered.get(0).label;
        String heading = doorAffix + " Door - Keystone [" + offer.level() + "]"
                + (ordered.isEmpty() ? "" : ", " + ordered.get(0).label.toLowerCase())
                + ".";
        DialogKit.show(player, DialogScreens.doorOffer(offer, step, heading));
    }
}
